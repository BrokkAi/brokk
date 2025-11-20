package ai.brokk.executor.jobs;

import ai.brokk.ContextManager;
import ai.brokk.MainProject;
import ai.brokk.executor.IssueFixRequest;
import ai.brokk.executor.JobReservation;
import ai.brokk.git.GitRepo;
import ai.brokk.git.GitWorkflow;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

/**
 * Orchestrates the automated issue-fixing workflow, including worktree creation,
 * fix execution, and pull request creation.
 */
public class IssueFixJobManager {
    private static final Logger logger = LogManager.getLogger(IssueFixJobManager.class);

    private final JobStore jobStore;
    private final ContextManager contextManager;
    private final JobRunner jobRunner;
    private final JobSpec jobSpec;
    private final IssueFixRequest request;
    private final String jobId;
    private final JobReservation jobReservation;
    private final int issueNumber;
    private final String issueTitle;
    private final String branchName;

    public IssueFixJobManager(
            JobStore jobStore,
            ContextManager contextManager,
            JobRunner jobRunner,
            JobSpec jobSpec,
            IssueFixRequest request,
            String jobId,
            JobReservation jobReservation,
            int issueNumber,
            String issueTitle,
            String branchName) {
        this.jobStore = jobStore;
        this.contextManager = contextManager;
        this.jobRunner = jobRunner;
        this.jobSpec = jobSpec;
        this.request = request;
        this.jobId = jobId;
        this.jobReservation = jobReservation;
        this.issueNumber = issueNumber;
        this.issueTitle = issueTitle;
        this.branchName = branchName;
    }

    /**
     * Execute the issue fix workflow asynchronously.
     * Orchestrates worktree creation, session setup, job execution, and PR creation.
     */
    public void execute() {
        logger.info(
                "Executing issue fix workflow: jobId={}, issue={}/{}/{}, branch={}",
                jobId,
                request.owner(),
                request.repo(),
                issueNumber,
                branchName);

        CompletableFuture.runAsync(() -> {
            try {
                // Step 1: Get worktree storage directory and create worktree
                var project = contextManager.getProject();
                if (!(project instanceof MainProject mainProject)) {
                    updateJobStatusFailed("Project is not a MainProject");
                    return;
                }

                var repo = mainProject.getRepo();
                if (!(repo instanceof GitRepo gitRepo)) {
                    updateJobStatusFailed("Repository is not a Git repository");
                    return;
                }

                var worktreeStorageDir = mainProject.getWorktreeStoragePath();
                var worktreePath = gitRepo.worktrees().getNextWorktreePath(worktreeStorageDir);

                logger.info("Creating worktree at {} for branch {}", worktreePath, branchName);
                gitRepo.worktrees().addWorktree(branchName, worktreePath);
                logger.info("Worktree created successfully at {}", worktreePath);

                // Step 2: Create a session for the worktree
                var sessionName = "Issue #" + issueNumber + ": " + issueTitle;
                var frozenContext = contextManager.liveContext();
                contextManager.createSessionWithoutGui(frozenContext, sessionName);
                logger.info("Session created for worktree: {}", sessionName);

                // Step 3: Run the job asynchronously
                jobRunner.runAsync(jobId, jobSpec).whenComplete((unused, throwable) -> {
                    if (throwable != null) {
                        logger.error("Issue fix job {} execution failed", jobId, throwable);
                        updateJobStatusFailed("Job execution failed: " + throwable.getMessage());
                    } else {
                        logger.info("Issue fix job {} execution finished successfully, creating PR.", jobId);
                        try {
                            var workflowService = new GitWorkflow(contextManager);
                            var targetBranch = gitRepo.getDefaultBranch();
                            var prTitle = String.format("Fixes #%d: %s", issueNumber, issueTitle);
                            var prBody = String.format("Automated fix for issue #%d.", issueNumber);

                            var prUri = workflowService.createPullRequest(branchName, targetBranch, prTitle, prBody);
                            var prUrl = prUri.toString();
                            logger.info("Created PR for job {}: {}", jobId, prUrl);

                            var result = Map.of("pullRequestUrl", prUrl);
                            updateJobStatusCompleted(result);
                        } catch (Exception prEx) {
                            logger.error("Failed to create pull request for job {}", jobId, prEx);
                            updateJobStatusFailed("Fix generated, but failed to create PR: " + prEx.getMessage());
                        }
                    }
                    jobReservation.releaseIfOwner(jobId);
                });
            } catch (Exception ex) {
                logger.error("Error setting up issue fix workflow for job {}", jobId, ex);
                updateJobStatusFailed("Failed to set up workflow: " + ex.getMessage());
                jobReservation.releaseIfOwner(jobId);
            }
        });
    }

    private void updateJobStatusFailed(String errorMessage) {
        try {
            var failedStatus = JobStatus.queued(jobId)
                    .withState(JobStatus.State.FAILED.toString())
                    .failed(errorMessage);
            jobStore.updateStatus(jobId, failedStatus);
            logger.info("Updated job {} status to FAILED: {}", jobId, errorMessage);
        } catch (Exception ex) {
            logger.error("Error updating job status for {}", jobId, ex);
        }
    }

    private void updateJobStatusCompleted(@Nullable Object result) {
        try {
            var completedStatus = JobStatus.queued(jobId)
                    .withState(JobStatus.State.COMPLETED.toString())
                    .completed(result);
            jobStore.updateStatus(jobId, completedStatus);
            logger.info("Updated job {} status to COMPLETED with result: {}", jobId, result);
        } catch (Exception ex) {
            logger.error("Error updating job status for {}", jobId, ex);
        }
    }
}
