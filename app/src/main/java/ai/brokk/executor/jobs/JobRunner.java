package ai.brokk.executor.jobs;

import ai.brokk.ContextManager;
import ai.brokk.IConsoleIO;
import ai.brokk.Service;
import ai.brokk.TaskResult;
import ai.brokk.agents.CodeAgent;
import ai.brokk.agents.SearchAgent;
import ai.brokk.context.ContextFragment;
import ai.brokk.executor.io.HeadlessHttpConsole;
import ai.brokk.gui.util.GitRepoIdUtil;
import ai.brokk.tasks.TaskList;
import ai.brokk.util.Json;
import com.fasterxml.jackson.core.type.TypeReference;
import com.google.common.base.Splitter;
import dev.langchain4j.model.chat.StreamingChatModel;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;
import org.kohsuke.github.GHPullRequest;
import org.kohsuke.github.GHPullRequestFileDetail;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHubBuilder;

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

                // To use it with set or get methods
                AtomicReference<Object> completionResultRef = new AtomicReference<>(null);

                List<TaskList.TaskItem> tasks;
                if (mode == Mode.REVIEW) {
                    // For REVIEW mode, create a single synthetic task
                    tasks = List.of(new TaskList.TaskItem(null, "Review", false));
                } else {
                    // For other modes, parse tasks from spec.taskInput (split by newlines, trim, ignore blanks)
                    tasks = parseTasks(spec.taskInput());
                }
                int total = tasks.size();
                var completed = new java.util.concurrent.atomic.AtomicInteger(0);

                logger.info("Job {} parsed {} task(s)", jobId, total);

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
                final StreamingChatModel reviewCodeModel = mode == Mode.REVIEW
                        ? (hasCodeModelOverride
                                ? resolveModelOrThrow(Objects.requireNonNull(trimmedCodeModelName))
                                : defaultCodeModel())
                        : null;
                final StreamingChatModel askPlannerModel =
                        mode == Mode.ASK ? resolveModelOrThrow(spec.plannerModel()) : null;
                final StreamingChatModel codeModeModel = mode == Mode.CODE
                        ? (hasCodeModelOverride
                                ? resolveModelOrThrow(Objects.requireNonNull(trimmedCodeModelName))
                                : defaultCodeModel())
                        : null;

                var service = cm.getService();
                String plannerModelNameForLog =
                        switch (mode) {
                            case ARCHITECT, LUTZ -> service.nameOf(Objects.requireNonNull(architectPlannerModel));
                            case ASK -> service.nameOf(Objects.requireNonNull(askPlannerModel));
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
                            case CODE -> service.nameOf(Objects.requireNonNull(codeModeModel));
                            case REVIEW -> service.nameOf(Objects.requireNonNull(reviewCodeModel));
                        };
                boolean usesDefaultCodeModel =
                        switch (mode) {
                            case ARCHITECT, LUTZ -> !hasCodeModelOverride;
                            case ASK -> true;
                            case CODE -> !hasCodeModelOverride;
                            case REVIEW -> !hasCodeModelOverride;
                        };
                if (plannerModelNameForLog == null || plannerModelNameForLog.isBlank()) {
                    plannerModelNameForLog = mode == Mode.CODE ? "(unused)" : "(unknown)";
                }
                if (codeModelNameForLog == null || codeModelNameForLog.isBlank()) {
                    codeModelNameForLog = usesDefaultCodeModel ? "(default)" : "(unknown)";
                } else if (usesDefaultCodeModel && mode != Mode.ASK) {
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
                                                            "code model unavailable for ARCHITECT jobs"));
                                        }
                                        case LUTZ -> {
                                            // Phase 1: Use SearchAgent to generate a task list from the initial task
                                            try (var scope = cm.beginTask(task.text(), false)) {
                                                var context = cm.liveContext();
                                                var searchAgent = new SearchAgent(
                                                        context,
                                                        task.text(),
                                                        Objects.requireNonNull(
                                                                architectPlannerModel,
                                                                "plannerModel required for LUTZ jobs"),
                                                        SearchAgent.Objective.TASKS_ONLY,
                                                        scope);
                                                var taskListResult = searchAgent.execute();
                                                scope.append(taskListResult);
                                            }
                                            // Task list is now in the live context and persisted by the scope
                                            logger.debug("LUTZ Phase 1 complete: task list generated");

                                            // Phase 2: Check if task list was generated; if empty, mark job complete
                                            var generatedTasks =
                                                    cm.getTaskList().tasks();
                                            if (generatedTasks.isEmpty()) {
                                                var msg = "SearchAgent generated no tasks for: " + task.text();
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
                                                        "LUTZ will execute {} incomplete task(s)",
                                                        incompleteTasks.size());

                                                for (TaskList.TaskItem generatedTask : incompleteTasks) {
                                                    if (cancelled.get()) {
                                                        logger.info(
                                                                "LUTZ job {} execution cancelled during task iteration",
                                                                jobId);
                                                        return; // Exit submitLlmAction to avoid outer completion
                                                        // increment
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
                                            try (var scope = cm.beginTask(task.text(), false)) {
                                                var result = agent.runTask(task.text(), Set.of());
                                                scope.append(result);
                                            }
                                        }
                                        case ASK -> {
                                            // Read-only execution via SearchAgent with ANSWER_ONLY objective
                                            try (var scope = cm.beginTask(task.text(), false)) {
                                                var context = cm.liveContext();
                                                var searchAgent = new SearchAgent(
                                                        context,
                                                        task.text(),
                                                        Objects.requireNonNull(
                                                                askPlannerModel, "plannerModel required for ASK jobs"),
                                                        SearchAgent.Objective.ANSWER_ONLY,
                                                        scope);
                                                var result = searchAgent.execute();
                                                scope.append(result);
                                            }
                                        }
                                        case REVIEW -> {
                                            var prData = Json.getMapper().readTree(spec.taskInput());
                                            int prNumber =
                                                    prData.get("pr_number").asInt(0);
                                            String repoURL =
                                                    prData.get("repo_url").asText();

                                            // Create review prompt
                                            String reviewPrompt = managePRContext(prNumber, repoURL, spec);

                                            // Execute review using CodeAgent
                                            var agent = new CodeAgent(
                                                    cm,
                                                    Objects.requireNonNull(
                                                            reviewPlannerModel,
                                                            "plannel model unavailable for REVIEW jobs"));

                                            TaskResult result;
                                            try (var scope = cm.beginTask("Review", false)) {
                                                result = agent.runTask(reviewPrompt, Set.of());
                                                scope.append(result);
                                            }
                                            String jsonString =
                                                    result.stopDetails().explanation();
                                            if (!jsonString.isBlank()) {
                                                Object parsed = Json.fromJson(
                                                        jsonString, new TypeReference<Map<String, Object>>() {});
                                                completionResultRef.set(parsed);
                                            }
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
                // - For ARCHITECT/LUTZ: per-task compression already honored via spec.autoCompress().
                if (mode != Mode.ARCHITECT && mode != Mode.LUTZ && spec.autoCompress()) {
                    logger.info("Job {} auto-compressing history", jobId);
                    cm.compressHistory();
                }

                // Determine final status: completed or cancelled
                JobStatus current = store.loadStatus(jobId);
                if (current != null) {
                    if (cancelled.get()) {
                        current = current.cancelled();
                        logger.info("Job {} marked as CANCELLED", jobId);
                    } else {
                        current = current.completed(completionResultRef.get());
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

    /**
     * Manages pull request context by fetching PR data, extracting diff information,
     * and generating a formatted review prompt with project guidelines.
     *
     * @param prNumber The pull request number
     * @param repoUrl The GitHub repository URL
     * @return Formatted review prompt string with JSON output instructions
     * @throws IOException If GitHub API communication fails
     * @throws IllegalArgumentException If the repository URL is invalid
     */
    private String managePRContext(int prNumber, String repoUrl, JobSpec spec) throws IOException {
        var ownerRepo = GitRepoIdUtil.parseOwnerRepoFromUrl(repoUrl);
        if (ownerRepo == null) {
            throw new IllegalArgumentException("Invalid GitHub URL: " + repoUrl);
        }

        String sessionIdStr = spec.tags().get("session_id");
        if (sessionIdStr == null || sessionIdStr.isBlank()) {
            throw new IllegalStateException("Session ID not found in job specification");
        }

        String authToken = spec.tags().get("github_token");
        if (authToken == null || authToken.isBlank()) {
            throw new IllegalStateException("GitHub authentication token not found in job specification");
        }

        UUID sessionId = UUID.fromString(sessionIdStr);
        try {
            cm.updateActiveSession(sessionId);
            logger.info("Switched session successfully for PR review");
        } catch (Exception e) {
            throw new RuntimeException("Failed to switch session for review", e);
        }

        String owner = ownerRepo.owner();
        String repoName = ownerRepo.repo();

        var github = new GitHubBuilder().withOAuthToken(authToken).build();
        GHRepository ghRepo = github.getRepository(owner + "/" + repoName);
        GHPullRequest pr = ghRepo.getPullRequest(prNumber);

        try {
            StringBuilder diff = new StringBuilder();

            for (GHPullRequestFileDetail file : pr.listFiles()) {
                String patch = file.getPatch();

                if (patch != null) {
                    diff.append("diff --git a/")
                            .append(file.getFilename())
                            .append(" b/")
                            .append(file.getFilename())
                            .append("\n")
                            .append(patch)
                            .append("\n");
                }
            }

            String fullDiff = diff.toString();
            String description = pr.getBody();

            var fragment = new ContextFragment.StringFragment(cm, fullDiff, description, "text/x-diff");
            cm.addFragments(fragment);

            String reviewGuide = cm.getProject().getReviewGuide();

            return String.format(
                    """
                Please review the following Pull Request:
                PR #%d: %s
                %s
                Review Guidelines:
                %s

                Please provide a thorough code review based on the diff and files in context.
                IMPORTANT: You must output ONLY valid JSON with this exact schema (no markdown fences, no extra text):
                {
                "action": "REQUEST_CHANGES" | "APPROVE" | "COMMENT",
                "comments": [
                    {
                    "file": "relative/path/to/file",
                    "line": 123,
                    "comment": "Specific issue description"
                    }
                ],
                "summary": "Overall review summary"
                }

                For each issue you find, create a comment entry with the exact file path from the changed_files, line number, and a concise description.
                Use action=REQUEST_CHANGES for blocking issues, APPROVE if no issues, COMMENT for minor suggestions.
                """,
                    prNumber, pr.getTitle(), description, reviewGuide);

        } catch (Exception e) {
            throw new RuntimeException("Failed to build review context for PR #" + prNumber, e);
        }
    }
}
