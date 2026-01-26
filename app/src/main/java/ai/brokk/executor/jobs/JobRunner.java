package ai.brokk.executor.jobs;

import ai.brokk.ContextManager;
import ai.brokk.GitHubAuth;
import ai.brokk.IConsoleIO;
import ai.brokk.Llm;
import ai.brokk.Service;
import ai.brokk.TaskResult;
import ai.brokk.agents.BuildAgent;
import ai.brokk.agents.CodeAgent;
import ai.brokk.agents.LutzAgent;
import ai.brokk.agents.SearchAgent;
import ai.brokk.context.Context;
import ai.brokk.executor.io.HeadlessHttpConsole;
import ai.brokk.git.GitRepo;
import ai.brokk.git.GitWorkflow;
import ai.brokk.issues.GitHubIssueService;
import ai.brokk.issues.IssueHeader;
import ai.brokk.project.IProject;
import ai.brokk.prompts.SearchPrompts;
import ai.brokk.tasks.TaskList;
import ai.brokk.util.TextUtil;
import ai.brokk.executor.jobs.modes.*;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiChatRequestParameters;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jgit.api.errors.GitAPIException;
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
    private final JobModelResolver modelResolver;

    static final int ISSUE_PROMPT_ENRICHMENT_WORD_THRESHOLD = 100;

    enum Mode {
        ARCHITECT,
        CODE,
        ASK,
        SEARCH,
        REVIEW,
        LUTZ,
        PLAN,
        ISSUE,
        ISSUE_WRITER
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
        this.modelResolver = new JobModelResolver(cm);
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

                var completed = new AtomicInteger(0);

                var rawCodeModelName = spec.codeModel() != null
                        ? spec.codeModel()
                        : spec.tags().get("code_model");
                var trimmedCodeModelName =
                        (rawCodeModelName != null && !rawCodeModelName.isBlank()) ? rawCodeModelName.trim() : null;
                var hasCodeModelOverride = trimmedCodeModelName != null;

                final StreamingChatModel architectPlannerModel =
                        (mode == Mode.ARCHITECT || mode == Mode.LUTZ || mode == Mode.ISSUE || mode == Mode.PLAN)
                                ? modelResolver.resolveModelOrThrow(spec.plannerModel(), spec.reasoningLevel(), spec.temperature())
                                : null;
                final StreamingChatModel architectCodeModel =
                        (mode == Mode.ARCHITECT || mode == Mode.LUTZ || mode == Mode.ISSUE)
                                ? (trimmedCodeModelName != null
                                        ? modelResolver.resolveModelOrThrow(
                                                trimmedCodeModelName, spec.reasoningLevelCode(), spec.temperatureCode())
                                        : modelResolver.defaultCodeModel(spec))
                                : null;
                final StreamingChatModel reviewPlannerModel = mode == Mode.REVIEW
                        ? modelResolver.resolveModelOrThrow(spec.plannerModel(), spec.reasoningLevel(), spec.temperature())
                        : null;
                // Resolve scan model for REVIEW mode (prefer explicit spec.scanModel() if provided; otherwise project
                // default)
                final StreamingChatModel reviewScanModel = mode == Mode.REVIEW
                        ? (spec.scanModel() != null && !spec.scanModel().trim().isEmpty()
                                ? modelResolver.resolveModelOrThrow(
                                        spec.scanModel().trim(), spec.reasoningLevel(), spec.temperature())
                                : modelResolver.defaultScanModel(spec))
                        : null;
                final StreamingChatModel askPlannerModel = mode == Mode.ASK || mode == Mode.ISSUE
                        ? modelResolver.resolveModelOrThrow(spec.plannerModel(), spec.reasoningLevel(), spec.temperature())
                        : null;
                final StreamingChatModel codeModeModel = mode == Mode.CODE
                        ? (hasCodeModelOverride
                                ? modelResolver.resolveModelOrThrow(
                                        Objects.requireNonNull(trimmedCodeModelName),
                                        spec.reasoningLevelCode(),
                                        spec.temperatureCode())
                                : modelResolver.defaultCodeModel(spec))
                        : null;

                var service = cm.getService();

                // Resolve a scan model for SEARCH mode if needed (prefer explicit spec.scanModel() if provided;
                // otherwise project default)
                final StreamingChatModel searchPlannerModel = mode == Mode.SEARCH
                        ? (spec.scanModel() != null && !spec.scanModel().trim().isEmpty()
                                ? modelResolver.resolveModelOrThrow(
                                        spec.scanModel().trim(), spec.reasoningLevel(), spec.temperature())
                                : modelResolver.defaultScanModel(spec))
                        : null;

                String plannerModelNameForLog =
                        switch (mode) {
                            case ARCHITECT, LUTZ, PLAN, ISSUE ->
                                service.nameOf(Objects.requireNonNull(architectPlannerModel));
                            case ASK -> service.nameOf(Objects.requireNonNull(askPlannerModel));
                            case SEARCH -> service.nameOf(Objects.requireNonNull(searchPlannerModel));
                            case CODE -> {
                                var plannerName = spec.plannerModel();
                                yield plannerName.isBlank() ? "(unused)" : plannerName.trim();
                            }
                            case REVIEW -> service.nameOf(Objects.requireNonNull(reviewPlannerModel));
                            case ISSUE_WRITER -> "(unused)";
                        };
                String codeModelNameForLog =
                        switch (mode) {
                            case ARCHITECT, LUTZ, ISSUE -> service.nameOf(Objects.requireNonNull(architectCodeModel));
                            case PLAN -> "(default, ignored for PLAN)";
                            case ASK -> "(default, ignored for ASK)";
                            case SEARCH -> "(default, ignored for SEARCH)";
                            case CODE -> service.nameOf(Objects.requireNonNull(codeModeModel));
                            case REVIEW -> "(default, ignored for REVIEW)";
                            case ISSUE_WRITER -> "(unused)";
                        };
                boolean usesDefaultCodeModel =
                        switch (mode) {
                            case ARCHITECT, LUTZ, ISSUE -> !hasCodeModelOverride;
                            case ASK, SEARCH, REVIEW, PLAN -> true;
                            case CODE -> !hasCodeModelOverride;
                            case ISSUE_WRITER -> true;
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
                // Prepare a compact execution context per-mode and delegate to per-mode handlers.
                {
                    // Choose the planner/code/scan models that are relevant for the selected mode.
                    final StreamingChatModel plannerForMode = switch (mode) {
                        case ARCHITECT, LUTZ, PLAN, ISSUE -> architectPlannerModel;
                        case ASK -> askPlannerModel;
                        case SEARCH -> searchPlannerModel;
                        case CODE -> codeModeModel;
                        case REVIEW -> reviewPlannerModel;
                        case ISSUE_WRITER -> null;
                    };
                    final StreamingChatModel codeForMode = switch (mode) {
                        case ARCHITECT, LUTZ, ISSUE -> architectCodeModel;
                        case CODE -> codeModeModel;
                        default -> null;
                    };
                    final StreamingChatModel scanForMode = switch (mode) {
                        case REVIEW -> reviewScanModel;
                        case SEARCH -> searchPlannerModel;
                        default -> null;
                    };

                    final JobExecutionContext execCtx = new JobExecutionContext(
                            jobId,
                            spec,
                            cm,
                            store,
                            console != null ? console : cm.getIo(),
                            cancelled::get,
                            plannerForMode,
                            codeForMode,
                            scanForMode);

                    cm.submitLlmAction(() -> {
                                if (cancelled.get()) {
                                    logger.info("Job {} execution cancelled by user", jobId);
                                    return;
                                }
                                try {
                                    switch (mode) {
                                        case ARCHITECT -> runArchitectMode(execCtx);
                                        case LUTZ -> runLutzMode(execCtx);
                                        case PLAN -> runPlanMode(execCtx);
                                        case CODE -> runCodeMode(execCtx);
                                        case ASK -> runAskMode(execCtx);
                                        case SEARCH -> runSearchMode(execCtx);
                                        case REVIEW -> runReviewMode(execCtx);
                                        case ISSUE -> IssueModeHandler.run(execCtx);
                                        case ISSUE_WRITER -> IssueWriterModeHandler.run(execCtx);
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
                }

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

    private record AppliedOverrides(
            Service.ModelConfig config, @Nullable OpenAiChatRequestParameters.Builder parametersOverride) {}

    private AppliedOverrides applyOverrides(
            Service.ModelConfig baseConfig,
            @Nullable String reasoningLevelOverride,
            @Nullable Double temperatureOverride) {
        Service.ReasoningLevel reasoning =
                Service.ReasoningLevel.fromString(reasoningLevelOverride, baseConfig.reasoning());

        var config = reasoning == baseConfig.reasoning()
                ? baseConfig
                : new Service.ModelConfig(baseConfig.name(), reasoning, baseConfig.tier());

        @Nullable OpenAiChatRequestParameters.Builder parametersOverride = null;
        if (temperatureOverride != null) {
            if (cm.getService().supportsTemperature(baseConfig.name())) {
                parametersOverride = OpenAiChatRequestParameters.builder().temperature(temperatureOverride);
            } else {
                logger.debug("Skipping temperature override for model {} as it is not supported.", baseConfig.name());
            }
        }

        return new AppliedOverrides(config, parametersOverride);
    }

    private StreamingChatModel resolveModelOrThrow(
            String name, @Nullable String reasoningLevelOverride, @Nullable Double temperatureOverride) {
        var service = cm.getService();

        var applied = applyOverrides(new Service.ModelConfig(name), reasoningLevelOverride, temperatureOverride);
        var model = service.getModel(applied.config(), applied.parametersOverride());
        if (model == null) {
            throw new IllegalArgumentException("MODEL_UNAVAILABLE: " + name);
        }
        return model;
    }

    private StreamingChatModel resolveModelOrThrow(
            Service.ModelConfig baseConfig,
            @Nullable String reasoningLevelOverride,
            @Nullable Double temperatureOverride) {
        var service = cm.getService();

        var applied = applyOverrides(baseConfig, reasoningLevelOverride, temperatureOverride);
        var model = service.getModel(applied.config(), applied.parametersOverride());
        if (model == null) {
            throw new IllegalArgumentException("MODEL_UNAVAILABLE: " + baseConfig.name());
        }
        return model;
    }

    private StreamingChatModel defaultCodeModel(JobSpec spec) {
        var service = cm.getService();
        var baseConfig = Service.ModelConfig.from(cm.getCodeModel(), service);
        return resolveModelOrThrow(baseConfig, spec.reasoningLevelCode(), spec.temperatureCode());
    }

    private StreamingChatModel defaultScanModel(JobSpec spec) {
        var service = cm.getService();
        var baseConfig = Service.ModelConfig.from(service.getScanModel(), service);
        return resolveModelOrThrow(baseConfig, spec.reasoningLevel(), spec.temperature());
    }

    /**
     * Package-private helper used by unit tests to verify which scan model name would be selected
     * for PLAN mode. If the JobSpec explicitly provides scanModel (non-blank) that value (trimmed)
     * is returned; otherwise the supplier is invoked to obtain the project-default scan model name.
     *
     * This helper is intentionally simple and returns the chosen model name rather than resolving
     * a StreamingChatModel so tests can assert model-selection semantics without requiring a full
     * Service/ContextManager environment.
     */
    static String chooseScanModelNameForPlan(JobSpec spec, java.util.function.Supplier<String> projectDefaultSupplier) {
        return JobModelResolver.chooseScanModelNameForPlan(spec, projectDefaultSupplier);
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
        messages = SearchPrompts.instance.buildAskPrompt(ctx, question, meta);
        // Create an LLM instance for the planner model and route output to the ContextManager IO
        var llm = cm.getLlm(new Llm.Options(model, "Answer: " + question).withEcho());
        llm.setOutput(cm.getIo());
        // Build and send the request to the LLM
        TaskResult.StopDetails stop = null;
        Llm.StreamingResult response = null;
        try {
            response = llm.sendRequest(messages);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
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
        IssueModeSupport.runSingleFixVerificationGate(jobId, store, io, verificationCommand, verificationRunner, fixTaskRunner);
    }

    static void runIssueModeTestLintRetryLoop(
            BooleanSupplier isCancelled,
            BiConsumer<Integer, String> progressSink,
            Function<String, String> commandRunner,
            Consumer<String> fixTaskRunner,
            BuildAgent.BuildDetails buildDetailsOverride,
            int maxIterations) {
        IssueModeSupport.runIssueModeTestLintRetryLoop("test-job", null, null, isCancelled, progressSink, commandRunner, fixTaskRunner, buildDetailsOverride, maxIterations);
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
        IssueModeSupport.runIssueModeTestLintRetryLoop(jobId, store, io, isCancelled, progressSink, commandRunner, fixTaskRunner, buildDetailsOverride, maxIterations);
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
        IssueModeSupport.runIssueReviewFixAttemptsWithCommandResultEvents(jobId, store, io, isCancelled, inlineComments, reviewFixTaskRunner, perTaskBranchUpdate);
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

    // New per-mode delegating methods ---------------------------------------------------------

    private void runArchitectMode(JobExecutionContext ctx) throws Exception {
        var jobId = ctx.jobId();
        var spec = ctx.spec();
        var cm = ctx.cm();
        var store = ctx.store();
        var console = ctx.io();
        var cancelledSupplier = ctx.cancelled();
        var plannerModel = ctx.plannerModel();
        var codeModel = ctx.codeModel();
        var scanModel = ctx.scanModel();

        cm.executeTask(
                new TaskList.TaskItem("", spec.taskInput(), false),
                Objects.requireNonNull(plannerModel, "plannerModel required for ARCHITECT jobs"),
                Objects.requireNonNull(codeModel, "code model unavailable for ARCHITECT jobs"));
    }

    private void runLutzMode(JobExecutionContext ctx) throws Exception {
        LutzModeHandler.run(ctx);
    }

    private void runPlanMode(JobExecutionContext ctx) throws Exception {
        PlanModeHandler.run(ctx);
    }

    private void runCodeMode(JobExecutionContext ctx) throws Exception {
        CodeModeHandler.run(ctx);
    }

    private void runAskMode(JobExecutionContext ctx) throws Exception {
        AskModeHandler.run(ctx);
    }

    private void runSearchMode(JobExecutionContext ctx) throws Exception {
        SearchModeHandler.run(ctx);
    }

    private void runReviewMode(JobExecutionContext ctx) throws Exception {
        ReviewModeHandler.run(ctx);
    }

}
