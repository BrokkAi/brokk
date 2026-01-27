package ai.brokk.executor.jobs;

import ai.brokk.ContextManager;
import ai.brokk.IConsoleIO;
import ai.brokk.agents.BuildAgent;
import ai.brokk.executor.io.HeadlessHttpConsole;
import ai.brokk.executor.jobs.modes.*;
import ai.brokk.git.GitRepo;
import ai.brokk.project.IProject;
import ai.brokk.tasks.TaskList;
import dev.langchain4j.model.chat.StreamingChatModel;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
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

    static final String COMMAND_RESULT_EVENT_TYPE = "COMMAND_RESULT";

    static final class IssueCancelledException extends IssueExecutionException {
        IssueCancelledException(String message) {
            super(message);
        }
    }

    static final PrReviewService.Severity DEFAULT_REVIEW_SEVERITY_THRESHOLD = PrReviewService.Severity.HIGH;
    static final int DEFAULT_REVIEW_MAX_INLINE_COMMENTS = 3;

    private final ContextManager cm;
    private final JobStore store;
    private final ExecutorService runner;
    private volatile @Nullable HeadlessHttpConsole console;
    private volatile @Nullable String activeJobId;
    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    enum Mode {
        ARCHITECT,
        CODE,
        ASK,
        SEARCH,
        REVIEW,
        LUTZ,
        PLAN,
        ISSUE,
        ISSUE_WRITER,
        TASK
    }

    static Mode parseMode(JobSpec spec) {
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
        final var currentConsole = new HeadlessHttpConsole(store, jobId);
        this.console = currentConsole;
        final var previousIo = cm.getIo();
        cm.setIo(currentConsole);
        cm.addContextListener(currentConsole);
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

                final JobModelResolver resolver = new JobModelResolver(cm);

                var rawCodeModelName = spec.codeModel() != null
                        ? spec.codeModel()
                        : spec.tags().get("code_model");
                var trimmedCodeModelName =
                        (rawCodeModelName != null && !rawCodeModelName.isBlank()) ? rawCodeModelName.trim() : null;

                // Resolve all potential models based on mode requirements
                final StreamingChatModel plannerModel =
                        switch (mode) {
                            case ARCHITECT, LUTZ, PLAN, ISSUE, REVIEW, ASK, TASK ->
                                resolver.resolveModelOrThrow(
                                        spec.plannerModel(), spec.reasoningLevel(), spec.temperature());
                            case CODE ->
                                trimmedCodeModelName != null
                                        ? resolver.resolveModelOrThrow(
                                                trimmedCodeModelName, spec.reasoningLevelCode(), spec.temperatureCode())
                                        : resolver.defaultCodeModel(spec);
                            case SEARCH, ISSUE_WRITER -> null;
                        };

                final StreamingChatModel codeModel =
                        switch (mode) {
                            case ARCHITECT, LUTZ, ISSUE, TASK ->
                                trimmedCodeModelName != null
                                        ? resolver.resolveModelOrThrow(
                                                trimmedCodeModelName, spec.reasoningLevelCode(), spec.temperatureCode())
                                        : resolver.defaultCodeModel(spec);
                            case CODE -> plannerModel; // plannerModel was already resolved as the code model above
                            default -> null;
                        };

                final StreamingChatModel scanModel =
                        switch (mode) {
                            case REVIEW, SEARCH, PLAN ->
                                (spec.scanModel() != null
                                                && !spec.scanModel().trim().isEmpty()
                                        ? resolver.resolveModelOrThrow(
                                                spec.scanModel().trim(), spec.reasoningLevel(), spec.temperature())
                                        : resolver.defaultScanModel(spec));
                            default -> null;
                        };

                logger.info("Job {} mode={} resolved", jobId, mode);

                final JobExecutionContext execCtx = new JobExecutionContext(
                        jobId, spec, cm, store, currentConsole, cancelled::get, plannerModel, codeModel, scanModel);

                cm.submitLlmAction(() -> {
                            if (cancelled.get()) {
                                logger.info("Job {} execution cancelled by user", jobId);
                                return;
                            }
                            switch (mode) {
                                case ARCHITECT -> runArchitectMode(execCtx);
                                case LUTZ -> LutzModeHandler.run(execCtx);
                                case PLAN -> PlanModeHandler.run(execCtx);
                                case CODE -> CodeModeHandler.run(execCtx);
                                case ASK -> AskModeHandler.run(execCtx);
                                case SEARCH -> SearchModeHandler.run(execCtx);
                                case REVIEW -> ReviewModeHandler.run(execCtx);
                                case ISSUE -> IssueModeHandler.run(execCtx);
                                case ISSUE_WRITER -> IssueWriterModeHandler.run(execCtx);
                                case TASK -> runTaskMode(execCtx);
                                default -> throw new IllegalStateException("Unhandled job mode: " + mode);
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
                var failure = unwrapFailure(t);

                boolean isCancellation = cancelled.get()
                        || failure instanceof IssueCancelledException
                        || t instanceof IssueCancelledException;

                if (isCancellation) {
                    logger.info("Job {} cancelled", jobId, failure);

                    try {
                        JobStatus s = store.loadStatus(jobId);
                        if (s == null) {
                            s = JobStatus.queued(jobId);
                        }
                        s = s.cancelled();
                        if (console != null) {
                            long lastSeq = console.getLastSeq();
                            s = s.withMetadata("lastSeq", Long.toString(lastSeq));
                        }
                        store.updateStatus(jobId, s);
                    } catch (Exception e2) {
                        logger.warn("Failed to persist CANCELLED status for job {}", jobId, e2);
                    }

                    future.complete(null);
                } else {
                    logger.error("Job {} execution failed", jobId, t);

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
                }
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
                if (console != null) {
                    cm.removeContextListener(console);
                }
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

    /**
     * Package-private helper used by unit tests to verify which scan model name would be selected
     * for PLAN mode.
     */
    static String chooseScanModelNameForPlan(JobSpec spec, java.util.function.Supplier<String> projectDefaultSupplier) {
        return JobModelResolver.chooseScanModelNameForPlan(spec, projectDefaultSupplier);
    }

    /**
     * Build the review prompt text for a given diff and comment policy.
     *
     * Package-private for tests to validate the policy text without invoking the LLM.
     */
    static String buildReviewPrompt(String diff, PrReviewService.Severity minSeverity, int maxComments) {
        return PrReviewPromptBuilder.buildReviewPrompt(diff, minSeverity, maxComments);
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

    static void cleanupIssueBranch(
            String jobId, GitRepo gitRepo, String originalBranch, String issueBranchName, boolean forceDelete) {
        IssueModeSupport.cleanupIssueBranch(jobId, gitRepo, originalBranch, issueBranchName, forceDelete);
    }

    static void runSingleFixVerificationGate(
            String jobId,
            JobStore store,
            IConsoleIO io,
            @Nullable String verificationCommand,
            Supplier<String> verificationRunner,
            Consumer<String> fixTaskRunner) {
        IssueModeSupport.runSingleFixVerificationGate(
                jobId, store, io, verificationCommand, verificationRunner, fixTaskRunner);
    }

    static void runIssueModeTestLintRetryLoop(
            BooleanSupplier isCancelled,
            BiConsumer<Integer, String> progressSink,
            Function<String, String> commandRunner,
            Consumer<String> fixTaskRunner,
            BuildAgent.BuildDetails buildDetailsOverride,
            int maxIterations) {
        IssueModeSupport.runIssueModeTestLintRetryLoop(
                "test-job",
                null,
                null,
                isCancelled,
                progressSink,
                commandRunner,
                fixTaskRunner,
                buildDetailsOverride,
                maxIterations);
    }

    static void runIssueModeTestLintRetryLoop(
            String jobId,
            JobStore store,
            IConsoleIO io,
            BooleanSupplier isCancelled,
            BiConsumer<Integer, String> progressSink,
            Function<String, String> commandRunner,
            Consumer<String> fixTaskRunner,
            BuildAgent.BuildDetails buildDetailsOverride,
            int maxIterations) {
        IssueModeSupport.runIssueModeTestLintRetryLoop(
                jobId,
                store,
                io,
                isCancelled,
                progressSink,
                commandRunner,
                fixTaskRunner,
                buildDetailsOverride,
                maxIterations);
    }

    static String maybeAnnotateDiffBlocks(String bodyMarkdown) {
        return IssueModeSupport.maybeAnnotateDiffBlocks(bodyMarkdown);
    }

    static BuildAgent.BuildDetails resolveIssueBuildDetails(JobSpec spec, IProject project) {
        String settings = spec.getBuildSettingsJson();
        if (settings != null && !settings.isBlank()) {
            return IssueService.parseBuildSettings(settings);
        }
        return project.awaitBuildDetails();
    }

    static boolean shouldEnrichIssuePrompt(@Nullable String body) {
        return IssueModeSupport.shouldEnrichIssuePrompt(body);
    }

    static boolean issueDeliveryEnabled(JobSpec spec) {
        return !"none".equalsIgnoreCase(spec.tags().getOrDefault("issue_delivery", ""));
    }

    static void runIssueReviewTaskSequence(
            List<PrReviewService.InlineComment> inlineComments,
            Function<PrReviewService.InlineComment, String> commentToPrompt,
            Consumer<String> taskRunner,
            Runnable branchUpdateHook,
            Runnable finalVerificationPass) {
        for (var comment : inlineComments) {
            taskRunner.accept(commentToPrompt.apply(comment));
            branchUpdateHook.run();
        }
        finalVerificationPass.run();
    }

    static void runIssueReviewTaskSequenceWithCancellation(
            List<PrReviewService.InlineComment> inlineComments,
            BooleanSupplier isCancelled,
            Function<PrReviewService.InlineComment, String> commentToPrompt,
            Consumer<String> taskRunner,
            Runnable branchUpdateHook,
            Runnable finalVerificationPass) {
        for (var comment : inlineComments) {
            if (isCancelled.getAsBoolean()) return;
            taskRunner.accept(commentToPrompt.apply(comment));
            branchUpdateHook.run();
        }
        if (isCancelled.getAsBoolean()) return;
        finalVerificationPass.run();
    }

    static void runIssueReviewFixAttemptsWithCommandResultEvents(
            String jobId,
            JobStore store,
            IConsoleIO io,
            BooleanSupplier isCancelled,
            List<PrReviewService.InlineComment> inlineComments,
            Consumer<PrReviewService.InlineComment> reviewFixTaskRunner,
            Runnable perTaskBranchUpdate) {
        IssueModeSupport.runIssueReviewFixAttemptsWithCommandResultEvents(
                jobId, store, io, isCancelled, inlineComments, reviewFixTaskRunner, perTaskBranchUpdate);
    }

    static List<PrReviewService.InlineComment> issueModeComputeInlineCommentsOrEmpty(
            Supplier<String> diffSupplier, Function<String, List<PrReviewService.InlineComment>> reviewAndParse) {
        String diff = diffSupplier.get();
        if (diff.isBlank()) return List.of();
        return reviewAndParse.apply(diff);
    }

    static String buildInlineCommentFixPrompt(PrReviewService.InlineComment comment) {
        return IssueModeSupport.buildInlineCommentFixPrompt(comment);
    }

    private void runArchitectMode(JobExecutionContext ctx) {
        try {
            ctx.cm()
                    .executeTask(
                            new TaskList.TaskItem("", ctx.spec().taskInput(), false),
                            Objects.requireNonNull(ctx.plannerModel(), "plannerModel required for ARCHITECT jobs"),
                            Objects.requireNonNull(ctx.codeModel(), "code model unavailable for ARCHITECT jobs"));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void runTaskMode(JobExecutionContext ctx) {
        String taskId = ctx.spec().tags().get("task_id");
        if (taskId == null) {
            throw new IllegalStateException("task_id tag required for TASK mode");
        }

        var taskOpt = ctx.cm().getTaskList().tasks().stream()
                .filter(t -> t.id().equals(taskId))
                .findFirst();

        if (taskOpt.isEmpty()) {
            throw new IllegalStateException("Task not found in current session: " + taskId);
        }

        try {
            ctx.cm()
                    .executeTask(
                            taskOpt.get(),
                            Objects.requireNonNull(ctx.plannerModel(), "plannerModel required for TASK jobs"),
                            Objects.requireNonNull(ctx.codeModel(), "code model unavailable for TASK jobs"));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
