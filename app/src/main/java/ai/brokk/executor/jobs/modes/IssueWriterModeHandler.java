package ai.brokk.executor.jobs.modes;

import ai.brokk.GitHubAuth;
import ai.brokk.agents.LutzAgent;
import ai.brokk.executor.jobs.IssueModeSupport;
import ai.brokk.executor.jobs.IssueWriterService;
import ai.brokk.executor.jobs.JobEvent;
import ai.brokk.executor.jobs.JobExecutionContext;
import ai.brokk.executor.jobs.JobModelResolver;
import ai.brokk.issues.GitHubIssueService;
import ai.brokk.prompts.SearchPrompts;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.Objects;

/**
 * Handler for ISSUE_WRITER mode.
 */
public final class IssueWriterModeHandler {
    private static final Logger logger = LogManager.getLogger(IssueWriterModeHandler.class);

    private IssueWriterModeHandler() {}

    public static void run(JobExecutionContext ctx) throws Exception {
        var jobId = ctx.jobId();
        var spec = ctx.spec();
        var cm = ctx.cm();
        var store = ctx.store();

        String githubToken = spec.getGithubToken();
        String repoOwner = spec.getRepoOwner();
        String repoName = spec.getRepoName();

        if (githubToken == null || githubToken.isBlank() || repoOwner == null || repoOwner.isBlank() || repoName == null || repoName.isBlank()) {
            throw new IllegalArgumentException("ISSUE_WRITER requires github_token, repo_owner, and repo_name in tags");
        }

        if (cm.getProject().isEmptyProject()) {
            throw new IllegalStateException("ISSUE_WRITER requires a materialized repository.");
        }

        try {
            store.appendEvent(jobId, JobEvent.of("NOTIFICATION", "ISSUE_WRITER: starting discovery"));
            try (var scope = cm.beginTaskUngrouped("Issue Writer")) {
                var model = new JobModelResolver(cm).resolveModelOrThrow(spec.plannerModel(), spec.reasoningLevel(), spec.temperature());
                var result = new LutzAgent(cm.liveContext(), "Issue Writer discovery for: " + spec.taskInput(), model, SearchPrompts.Objective.ISSUE_DIAGNOSIS, scope).execute();
                scope.append(result);

                var parsed = IssueWriterService.parseIssueResponse(result.output().text().join());
                if (parsed == null) throw new IllegalStateException("Discovery output was not valid JSON.");

                String finalBody = IssueModeSupport.maybeAnnotateDiffBlocks(parsed.bodyMarkdown());
                var created = new GitHubIssueService(cm.getProject(), new GitHubAuth(repoOwner, repoName, null, githubToken)).createIssue(parsed.title(), finalBody);

                String msg = "ISSUE_WRITER: issue created " + created.id() + " " + created.htmlUrl();
                store.appendEvent(jobId, JobEvent.of("NOTIFICATION", msg));
            }
        } catch (IOException ioe) {
            logger.warn("Failed to append event for job {}", jobId, ioe);
        }
    }
}
