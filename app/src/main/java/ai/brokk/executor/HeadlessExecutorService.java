package ai.brokk.executor;

import static java.nio.charset.StandardCharsets.UTF_8;

import ai.brokk.ContextManager;
import ai.brokk.MainProject;
import ai.brokk.SessionManager;
import ai.brokk.executor.jobs.JobEvent;
import ai.brokk.executor.jobs.JobStore;
import ai.brokk.executor.jobs.JobStore.JobCreateResult;
import ai.brokk.executor.jobs.JobRunner;
import ai.brokk.executor.jobs.JobSpec;
import ai.brokk.executor.jobs.JobStatus;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jgit.api.errors.GitAPIException;

/**
 * Encapsulates a single headless executor instance bound to one workspace/worktree.
 * Owns ContextManager, SessionManager, JobStore and JobRunner and provides the core
 * operations required by the HTTP layer or other callers.
 */
public final class HeadlessExecutorService implements AutoCloseable {
    private static final Logger logger = LogManager.getLogger(HeadlessExecutorService.class);

    private final UUID execId;
    private final Path workspaceDir;
    private final Path sessionsDir;

    private final SessionManager sessionManager;
    private final JobStore jobStore;
    private final ContextManager contextManager;
    private final JobRunner jobRunner;

    private final AtomicReference<UUID> currentSessionId = new AtomicReference<>();
    private final AtomicReference<String> currentJobId = new AtomicReference<>();

    public HeadlessExecutorService(UUID execId, Path workspaceDir, Path sessionsDir) throws IOException {
        this.execId = Objects.requireNonNull(execId);
        this.workspaceDir = Objects.requireNonNull(workspaceDir);
        this.sessionsDir = Objects.requireNonNull(sessionsDir);

        Files.createDirectories(sessionsDir);

        this.jobStore = new JobStore(workspaceDir.resolve(".brokk").resolve("jobs"));
        this.sessionManager = new SessionManager(sessionsDir);

        var project = new MainProject(workspaceDir);
        this.contextManager = new ContextManager(project);
        // Start headless context
        this.contextManager.createHeadless();

        this.jobRunner = new JobRunner(this.contextManager, this.jobStore);

        logger.info("HeadlessExecutorService initialized: execId={}, workspace={}, sessions={}",
                this.execId, this.workspaceDir, this.sessionsDir);
    }

    /**
     * Import a session zip into the sessions dir and switch the context to it.
     *
     * @param zipData binary zip contents
     * @param sessionId UUID to assign (caller may generate one)
     * @throws Exception on failure writing or switching session
     */
    public void importSessionZip(byte[] zipData, UUID sessionId) throws Exception {
        Objects.requireNonNull(zipData);
        Objects.requireNonNull(sessionId);

        var sessionZipPath = sessionsDir.resolve(sessionId + ".zip");
        Files.write(sessionZipPath, zipData, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        logger.info("Session zip stored: {} ({})", sessionId, sessionZipPath);

        // Switch ContextManager to this session (async then wait)
        this.contextManager.switchSessionAsync(sessionId).join();
        this.currentSessionId.set(sessionId);
        logger.info("Switched to session: {}", sessionId);
    }

    /**
     * Create or get a job in an idempotent manner. If the returned result indicates a newly-created job,
     * execution of the job will be started asynchronously by this service. If another job is currently running
     * this method will throw IllegalStateException to mirror existing behavior.
     *
     * @param idempotencyKey idempotency key
     * @param spec the JobSpec
     * @return JobCreateResult containing jobId and isNewJob flag
     * @throws Exception on failure creating or starting the job
     */
    public JobCreateResult createJob(String idempotencyKey, JobSpec spec) throws Exception {
        Objects.requireNonNull(idempotencyKey);
        Objects.requireNonNull(spec);

        // Mirror existing HTTP-layer guard: disallow creating a job while another is executing
        if (currentJobId.get() != null) {
            throw new IllegalStateException("A job is currently executing");
        }

        var result = jobStore.createOrGetJob(idempotencyKey, spec);
        var jobId = result.jobId();
        var isNew = result.isNewJob();

        logger.info("Job createOrGet: idempKey={}, jobId={}, isNew={}", idempotencyKey, jobId, isNew);

        if (isNew) {
            // record and start execution
            currentJobId.set(jobId);
            startJobExecutionAsync(jobId, spec);
        }

        return result;
    }

    private void startJobExecutionAsync(String jobId, JobSpec spec) {
        logger.info("Starting async execution for job {}", jobId);
        CompletableFuture<Void> fut = jobRunner.runAsync(jobId, spec);
        fut.whenComplete((unused, throwable) -> {
            if (throwable != null) {
                logger.error("Job {} execution failed", jobId, throwable);
            } else {
                logger.info("Job {} execution finished", jobId);
            }
            // clear currentJobId only if it still points to this jobId
            currentJobId.compareAndSet(jobId, null);
        });
    }

    /**
     * Load the current status for a job.
     *
     * @param jobId job id
     * @return JobStatus or null if not found
     */
    public JobStatus getJobStatus(String jobId) {
        Objects.requireNonNull(jobId);
        try {
            return jobStore.loadStatus(jobId);
        } catch (IOException e) {
            logger.error("Failed to load job status for {}", jobId, e);
            throw new RuntimeException("Failed to load job status", e);
        }
    }

    /**
     * Result holder containing events and the nextAfter cursor value.
     */
    public record JobEventsResult(List<JobEvent> events, long nextAfter) {}

    /**
     * Read job events after the provided sequence number.
     *
     * @param jobId job id
     * @param after sequence to read after (inclusive semantics preserved by underlying store)
     * @param limit maximum events
     * @return JobEventsResult containing events and nextAfter cursor
     * @throws Exception on underlying read failure
     */
    public JobEventsResult getJobEvents(String jobId, long after, int limit) throws Exception {
        Objects.requireNonNull(jobId);
        var events = jobStore.readEvents(jobId, after, limit);
        long nextAfter = after;
        if (!events.isEmpty()) {
            nextAfter = events.get(events.size() - 1).seq();
        }
        return new JobEventsResult(events, nextAfter);
    }

    /**
     * Request cancellation for a running job. The JobRunner is responsible for marking the job status.
     *
     * @param jobId job id
     */
    public void cancelJob(String jobId) {
        Objects.requireNonNull(jobId);
        logger.info("Cancelling job {}", jobId);
        this.jobRunner.cancel(jobId);
    }

    /**
     * Compute and return a git diff for the current workspace via ContextManager/Project.
     *
     * @return textual diff
     * @throws UnsupportedOperationException if git is not available in the workspace
     */
    public String getDiff() {
        try {
            var repo = contextManager.getProject().getRepo();
            return repo.diff();
        } catch (GitAPIException e) {
            logger.error("Git operation failed for execId={}", execId, e);
            throw new RuntimeException("Failed to compute git diff", e);
        } catch (UnsupportedOperationException e) {
            logger.info("Git not available for execId={}", execId);
            throw e;
        }
    }

    public UUID getCurrentSessionId() {
        return currentSessionId.get();
    }

    public String getCurrentJobId() {
        return currentJobId.get();
    }

    public java.util.UUID getExecId() {
        return execId;
    }

    @Override
    public void close() throws Exception {
        Exception last = null;
        try {
            this.contextManager.close();
        } catch (Exception e) {
            logger.warn("Error closing ContextManager", e);
            last = e;
        }
        try {
            this.sessionManager.close();
        } catch (Exception e) {
            logger.warn("Error closing SessionManager", e);
            last = e;
        }
        if (last != null) {
            throw last;
        }
    }
}
