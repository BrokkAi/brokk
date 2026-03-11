package ai.brokk.executor.jobs;

import ai.brokk.ContextManager;
import ai.brokk.IConsoleIO;
import ai.brokk.TaskResult;
import ai.brokk.agents.BuildAgent;
import ai.brokk.context.Context;
import ai.brokk.git.GitRepo;
import ai.brokk.project.IProject;
import dev.langchain4j.model.chat.StreamingChatModel;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.jetbrains.annotations.Nullable;

/**
 * Extracted helper logic for ISSUE-mode operations.
 */
public final class IssueService {
    private static final Logger logger = LogManager.getLogger(IssueService.class);

    private IssueService() {}

    /**
     * Resolves build details for ISSUE mode: uses spec's build_settings if present and non-blank,
     * otherwise falls back to project-level build details.
     */
    public static BuildAgent.BuildDetails resolveIssueBuildDetails(JobSpec spec, IProject project) {
        String buildSettingsJson = spec.getBuildSettingsJson();
        if (buildSettingsJson != null && !buildSettingsJson.isBlank()) {
            return parseBuildSettings(buildSettingsJson);
        }
        return project.awaitBuildDetails();
    }

    public static BuildAgent.BuildDetails parseBuildSettings(@Nullable String json) {
        if (json == null || json.isBlank()) {
            return BuildAgent.BuildDetails.EMPTY;
        }
        try {
            return ai.brokk.util.Json.getMapper().readValue(json, BuildAgent.BuildDetails.class);
        } catch (Exception e) {
            logger.warn("Failed to parse build settings JSON: {}", e.getMessage());
            return BuildAgent.BuildDetails.EMPTY;
        }
    }

    public static boolean issueDeliveryEnabled(JobSpec spec) {
        String raw = spec.tags().get("issue_delivery");
        if (raw == null || raw.isBlank()) {
            return true;
        }
        return !raw.trim().equalsIgnoreCase("none");
    }

    /**
     * Post a comment to a GitHub issue.
     */
    public static void postIssueComment(org.kohsuke.github.GHRepository ghRepo, int issueNumber, String body)
            throws IOException {
        ghRepo.getIssue(issueNumber).comment(body);
    }

    private static final String ALPHANUM = "abcdefghijklmnopqrstuvwxyz0123456789";
    private static final java.util.Random RANDOM = new java.util.Random();

    private static String randomId(int length) {
        var sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(ALPHANUM.charAt(RANDOM.nextInt(ALPHANUM.length())));
        }
        return sb.toString();
    }

    /**
     * Generates a unique branch name for an issue fix with a random suffix.
     */
    public static String generateBranchNameWithRandomSuffix(int issueNumber, GitRepo gitRepo) throws GitAPIException {
        return generateBranchName(issueNumber, gitRepo, randomId(6));
    }

    /**
     * Generates a unique branch name for an issue fix.
     */
    public static String generateBranchName(int issueNumber, GitRepo gitRepo, String suffix) throws GitAPIException {
        String baseName = "brokk/issue-" + issueNumber + "-" + suffix;
        return gitRepo.sanitizeBranchName(baseName);
    }

    /**
     * Builds a standard PR description including a reference to the issue.
     */
    public static String buildPrDescription(@Nullable String aiDescription, int issueNumber) {
        String base = (aiDescription == null || aiDescription.isBlank()) ? "" : aiDescription.trim() + "\n\n";
        return base + "Fixes #" + issueNumber;
    }

    public static void cleanupIssueBranch(
            String jobId, GitRepo gitRepo, String originalBranch, String issueBranchName, boolean forceDelete) {
        try {
            String currentBranch = null;
            try {
                currentBranch = gitRepo.getCurrentBranch();
            } catch (Exception e) {
                logger.warn("ISSUE job {}: Unable to determine current branch during cleanup", jobId, e);
            }

            if (!Objects.equals(currentBranch, originalBranch)) {
                try {
                    gitRepo.checkout(originalBranch);
                } catch (Exception checkoutEx) {
                    logger.warn("ISSUE job {}: Initial checkout failed during cleanup", jobId);
                    try {
                        if (!gitRepo.getModifiedFiles().isEmpty()) {
                            gitRepo.createStash("brokk-autostash-for-cleanup");
                            gitRepo.checkout(originalBranch);
                        }
                    } catch (Exception modEx) {
                        logger.warn("ISSUE job {}: Checkout retry failed during cleanup", jobId);
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("ISSUE job {}: Unexpected error during branch restoration cleanup", jobId, e);
        }

        try {
            try {
                gitRepo.deleteBranch(issueBranchName);
            } catch (Exception delEx) {
                if (forceDelete) {
                    try {
                        gitRepo.forceDeleteBranch(issueBranchName);
                    } catch (Exception forceEx) {
                        logger.warn("ISSUE job {}: Failed to force-delete branch '{}'", jobId, issueBranchName);
                    }
                } else {
                    logger.info("ISSUE job {}: Leaving branch '{}' (forceDelete=false)", jobId, issueBranchName);
                }
            }
        } catch (Exception e) {
            logger.warn("ISSUE job {}: Failed to delete issue branch '{}'", jobId, issueBranchName, e);
        }
    }

    public static void runIssueModeTestLintRetryLoop(
            String jobId,
            JobStore store,
            IConsoleIO io,
            BooleanSupplier isCancelled,
            BiConsumer<Integer, String> progressSink,
            Function<String, String> commandRunner,
            Consumer<String> fixTaskRunner,
            BuildAgent.BuildDetails buildDetailsOverride,
            int maxIterations) {
        if (maxIterations < 1) {
            throw new IllegalArgumentException("maxIterations must be >= 1");
        }

        String testCmd = buildDetailsOverride.testAllCommand();
        String lintCmd = buildDetailsOverride.buildLintCommand();

        boolean testsSkipped = testCmd.isBlank();
        boolean lintSkipped = lintCmd.isBlank();

        @Nullable String lastFailStage = null;
        @Nullable String lastFailCommand = null;
        @Nullable String lastFailOutput = null;

        for (int i = 0; i < maxIterations; i++) {
            int attemptNumber = i + 1;

            if (isCancelled.getAsBoolean()) {
                throw new JobRunner.IssueCancelledException("Cancelled during final verification (tests/lint)");
            }

            String startMsg = "Final verification attempt %d/%d: tests=%s, lint=%s"
                    .formatted(
                            attemptNumber, maxIterations, testsSkipped ? "SKIP" : "RUN", lintSkipped ? "SKIP" : "RUN");
            progressSink.accept(attemptNumber, startMsg);

            // 1. Run Tests
            String testOut = "";
            if (testsSkipped) {
                JobRunner.emitSkippedCommand(jobId, store, io, "tests", testCmd, attemptNumber, "blank_command");
            } else {
                testOut = JobRunner.runAndEmitCommand(jobId, store, io, "tests", testCmd, attemptNumber, commandRunner);
            }
            boolean testsPassed = testsSkipped || testOut.isBlank();

            if (!testsPassed) {
                lastFailStage = "tests";
                lastFailCommand = testCmd;
                lastFailOutput = testOut;

                // Emit skipped lint event for the same attempt when tests fail
                if (lintSkipped) {
                    JobRunner.emitSkippedCommand(jobId, store, io, "lint", lintCmd, attemptNumber, "blank_command");
                } else {
                    JobRunner.emitSkippedCommand(jobId, store, io, "lint", lintCmd, attemptNumber, "tests_failed");
                }

                fixTaskRunner.accept(testOut);
                continue;
            }

            if (isCancelled.getAsBoolean()) {
                throw new JobRunner.IssueCancelledException("Cancelled during final verification (tests/lint)");
            }

            // 2. Run Lint
            String lintOut = "";
            if (lintSkipped) {
                JobRunner.emitSkippedCommand(jobId, store, io, "lint", lintCmd, attemptNumber, "blank_command");
            } else {
                lintOut = JobRunner.runAndEmitCommand(jobId, store, io, "lint", lintCmd, attemptNumber, commandRunner);
            }
            boolean lintPassed = lintSkipped || lintOut.isBlank();

            if (lintPassed) {
                return;
            }

            lastFailStage = "lint";
            lastFailCommand = lintCmd;
            lastFailOutput = lintOut;

            fixTaskRunner.accept(lintOut);
        }

        String baseMessage = "Tests/lint failed after " + maxIterations + " iteration(s)";
        if (lastFailStage != null && lastFailCommand != null && lastFailOutput != null && !lastFailOutput.isBlank()) {
            throw new IssueExecutionException(baseMessage + ". Last failure: stage=" + lastFailStage + ", command="
                    + lastFailCommand + "\nOutput:\n" + lastFailOutput);
        }

        throw new IssueExecutionException(baseMessage);
    }

    public static void runIssueReviewFixAttemptsWithCommandResultEvents(
            String jobId,
            JobStore store,
            IConsoleIO io,
            BooleanSupplier isCancelled,
            List<PrReviewService.InlineComment> inlineComments,
            Consumer<PrReviewService.InlineComment> reviewFixTaskRunner,
            Runnable perTaskBranchUpdate) {

        for (int i = 0; i < inlineComments.size(); i++) {
            int attempt = i + 1;
            var comment = inlineComments.get(i);

            String path = Objects.requireNonNullElse(comment.path(), "");
            int line = comment.line();
            String command = path + ":" + line;

            String severity = comment.severity().name();
            String body = Objects.requireNonNullElse(comment.bodyMarkdown(), "").trim();

            String contextBlock =
                    """
                    Finding:
                    - path: %s
                    - line: %d
                    - severity: %s
                    - bodyMarkdown:
                    %s
                    """
                            .formatted(path, line, severity, body.isEmpty() ? "(empty)" : body);

            if (isCancelled.getAsBoolean()) {
                for (int j = i; j < inlineComments.size(); j++) {
                    int skippedAttempt = j + 1;
                    var skipped = inlineComments.get(j);
                    String skippedPath = Objects.requireNonNullElse(skipped.path(), "");
                    int skippedLine = skipped.line();
                    String skippedCommand = skippedPath + ":" + skippedLine;

                    String skippedSeverity = skipped.severity().name();
                    String skippedBody = Objects.requireNonNullElse(skipped.bodyMarkdown(), "")
                            .trim();

                    String skippedContextBlock =
                            """
                            Finding:
                            - path: %s
                            - line: %d
                            - severity: %s
                            - bodyMarkdown:
                            %s

                            Outcome: skipped
                            """
                                    .formatted(
                                            skippedPath,
                                            skippedLine,
                                            skippedSeverity,
                                            skippedBody.isEmpty() ? "(empty)" : skippedBody);

                    JobRunner.emitCommandResult(
                            jobId,
                            store,
                            io,
                            JobRunner.commandResult(
                                    "review_fix",
                                    skippedCommand,
                                    skippedAttempt,
                                    /* skipped= */ true,
                                    /* skipReason= */ "cancelled",
                                    /* success= */ true,
                                    skippedContextBlock,
                                    null),
                            "review_fix: SKIP");
                }
                return;
            }

            try {
                reviewFixTaskRunner.accept(comment);
                perTaskBranchUpdate.run();

                String output = contextBlock + "\nOutcome: completed\n";
                JobRunner.emitCommandResult(
                        jobId,
                        store,
                        io,
                        JobRunner.commandResult(
                                "review_fix",
                                command,
                                attempt,
                                /* skipped= */ false,
                                /* skipReason= */ null,
                                /* success= */ true,
                                output,
                                null),
                        "review_fix: PASS");
            } catch (RuntimeException re) {
                String output = contextBlock + "\nOutcome: failed\n";
                JobRunner.emitCommandResult(
                        jobId,
                        store,
                        io,
                        JobRunner.commandResult(
                                "review_fix",
                                command,
                                attempt,
                                /* skipped= */ false,
                                /* skipReason= */ null,
                                /* success= */ false,
                                output,
                                re),
                        "review_fix: ERROR");
                throw re;
            }
        }
    }

    public static List<PrReviewService.InlineComment> issueModeComputeInlineCommentsOrEmpty(
            Supplier<String> diffSupplier, Function<String, List<PrReviewService.InlineComment>> reviewAndParse) {
        String diff = diffSupplier.get();
        if (diff.isBlank()) {
            return List.of();
        }
        return reviewAndParse.apply(diff);
    }

    public static List<PrReviewService.InlineComment> issueModeComputeInlineComments(
            String jobId,
            JobStore store,
            GitRepo gitRepo,
            Context ctx,
            StreamingChatModel reviewModel,
            String githubToken,
            String baseBranch,
            ContextManager cm) {

        String remoteName = gitRepo.remote().getOriginRemoteNameWithFallback();
        if (remoteName != null) {
            try {
                gitRepo.remote().fetchBranch(remoteName, baseBranch, githubToken);
            } catch (GitAPIException e) {
                logger.warn(
                        "ISSUE job {}: failed to fetch base branch '{}' from remote '{}': {}",
                        jobId,
                        baseBranch,
                        remoteName,
                        e.getMessage());
            }
        }

        String baseRef = remoteName != null ? remoteName + "/" + baseBranch : baseBranch;

        return issueModeComputeInlineCommentsOrEmpty(
                (Supplier<String>) () -> {
                    try {
                        return PrReviewService.computePrDiff(gitRepo, baseRef, "HEAD");
                    } catch (GitAPIException e) {
                        throw new IssueExecutionException(
                                "Failed to compute diff for issue review (baseRef=" + baseRef + "): " + e.getMessage(),
                                e);
                    }
                },
                diff -> {
                    String annotatedDiff = PrReviewService.annotateDiffWithLineNumbers(diff);
                    if (annotatedDiff.isBlank()) {
                        return List.of();
                    }

                    PrReviewExecutor.ReviewDiffResult review;
                    try {
                        try (var reviewScope = cm.beginTaskUngrouped("PR Review")) {
                            review = new PrReviewExecutor(cm)
                                    .reviewDiff(
                                            ctx,
                                            reviewModel,
                                            annotatedDiff,
                                            "",
                                            "",
                                            JobRunner.DEFAULT_REVIEW_SEVERITY_THRESHOLD);
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IssueExecutionException("Interrupted while running PR Review", e);
                    }
                    String reviewText = review.responseText();

                    var reviewResponse = PrReviewService.parsePrReviewResponse(reviewText);
                    if (reviewResponse == null) {
                        if (reviewText.isBlank()) {
                            var stopDetails = review.taskResult().stopDetails();
                            String causeDetail = stopDetails.reason() == TaskResult.StopReason.SUCCESS
                                    ? ""
                                    : " Cause: "
                                            + stopDetails
                                                    .explanation()
                                                    .lines()
                                                    .findFirst()
                                                    .orElse(stopDetails.reason().name());
                            throw new IssueExecutionException(
                                    "LLM returned empty response for issue diff review." + causeDetail);
                        }
                        String preview = reviewText.length() > 500 ? reviewText.substring(0, 500) + "..." : reviewText;
                        throw new IssueExecutionException(
                                "Issue diff review response was not valid JSON. Response preview: " + preview);
                    }

                    var filtered = PrReviewService.filterInlineComments(
                            reviewResponse.comments(),
                            JobRunner.DEFAULT_REVIEW_SEVERITY_THRESHOLD,
                            JobRunner.DEFAULT_REVIEW_MAX_INLINE_COMMENTS);

                    if (!filtered.isEmpty()) {
                        try {
                            store.appendEvent(
                                    jobId,
                                    JobEvent.of(
                                            "NOTIFICATION",
                                            "Review-bot: generated " + filtered.size()
                                                    + " inline comment(s) (severity >= "
                                                    + JobRunner.DEFAULT_REVIEW_SEVERITY_THRESHOLD + ")"));
                        } catch (IOException e) {
                            logger.warn(
                                    "Failed to append review-bot notification event for job {}: {}",
                                    jobId,
                                    e.getMessage(),
                                    e);
                        }
                    }

                    return filtered;
                });
    }

    public static String buildInlineCommentFixPrompt(PrReviewService.InlineComment comment) {
        String path = Objects.requireNonNullElse(comment.path(), "");
        int line = comment.line();

        var sev = comment.severity();
        String severity = sev.name();

        String body = Objects.requireNonNullElse(comment.bodyMarkdown(), "").trim();

        return """
                You are fixing a review inline comment on the CURRENT issue branch.

                Inline comment details:
                - path: %s
                - line: %d
                - severity: %s
                - bodyMarkdown:
                %s

                Instructions:
                - Implement the fix described above in the repository.
                - Make the minimal correct change(s) to address the issue.
                - Do NOT switch branches. Work only on the current issue branch.
                - Ensure the code compiles and tests remain passing where applicable.
                """
                .formatted(path, line, severity, body.isEmpty() ? "(empty)" : body);
    }

    public static void runSingleFixVerificationGate(
            String jobId,
            JobStore store,
            IConsoleIO io,
            @Nullable String verificationCommand,
            Supplier<String> verificationRunner,
            Consumer<String> fixTaskRunner) {
        String commandLabel = verificationCommand == null ? "" : verificationCommand;

        // First verification
        final String firstOut;
        try {
            firstOut = Objects.requireNonNullElse(verificationRunner.get(), "");
        } catch (RuntimeException re) {
            JobRunner.emitCommandResult(
                    jobId,
                    store,
                    io,
                    JobRunner.commandResult(
                            "verification",
                            commandLabel,
                            1,
                            /* skipped= */ false,
                            /* skipReason= */ null,
                            false,
                            "",
                            re),
                    "Verification: ERROR");

            String exMsg = re.getMessage();
            String detail = (exMsg == null || exMsg.isBlank()) ? re.getClass().getSimpleName() : exMsg;
            throw new IssueExecutionException("Verification runner failed: " + detail, re);
        }

        boolean passedFirst = firstOut.isBlank();
        JobRunner.emitCommandResult(
                jobId,
                store,
                io,
                JobRunner.commandResult(
                        "verification",
                        commandLabel,
                        1,
                        /* skipped= */ false,
                        /* skipReason= */ null,
                        passedFirst,
                        firstOut,
                        null),
                "Verification: " + (passedFirst ? "PASS" : "FAIL"));

        if (passedFirst) {
            return;
        }

        // Surface failure output when triggering the fix attempt.
        JobRunner.emitCommandResult(
                jobId,
                store,
                io,
                JobRunner.commandResult(
                        "fix_trigger",
                        commandLabel,
                        1,
                        /* skipped= */ false,
                        /* skipReason= */ null,
                        false,
                        firstOut,
                        null),
                "Fix attempt: TRIGGERED");

        // Perform exactly one fix attempt
        String prompt = "Verification failed. Output:\n" + firstOut + "\n\nPlease make a single fix attempt.";
        fixTaskRunner.accept(prompt);

        // Re-run verification exactly once
        final String secondOut;
        try {
            secondOut = Objects.requireNonNullElse(verificationRunner.get(), "");
        } catch (RuntimeException re) {
            JobRunner.emitCommandResult(
                    jobId,
                    store,
                    io,
                    JobRunner.commandResult(
                            "verification",
                            commandLabel,
                            2,
                            /* skipped= */ false,
                            /* skipReason= */ null,
                            false,
                            "",
                            re),
                    "Verification after fix: ERROR");

            String exMsg = re.getMessage();
            String detail = (exMsg == null || exMsg.isBlank()) ? re.getClass().getSimpleName() : exMsg;
            throw new IssueExecutionException("Verification runner failed after fix: " + detail, re);
        }

        boolean passedSecond = secondOut.isBlank();
        JobRunner.emitCommandResult(
                jobId,
                store,
                io,
                JobRunner.commandResult(
                        "verification",
                        commandLabel,
                        2,
                        /* skipped= */ false,
                        /* skipReason= */ null,
                        passedSecond,
                        secondOut,
                        null),
                "Verification after fix: " + (passedSecond ? "PASS" : "FAIL"));

        if (passedSecond) {
            return;
        }

        throw new IssueExecutionException("Verification failed after single fix attempt:\n\n" + secondOut);
    }

    public static void runIssueReviewTaskSequence(
            List<PrReviewService.InlineComment> inlineComments,
            Function<PrReviewService.InlineComment, String> commentToPrompt,
            Consumer<String> taskRunner,
            Runnable branchUpdateHook,
            Runnable finalVerificationPass) {

        for (var comment : inlineComments) {
            String prompt = commentToPrompt.apply(comment);
            taskRunner.accept(prompt);
            branchUpdateHook.run();
        }

        finalVerificationPass.run();
    }

    public static void runIssueReviewTaskSequenceWithCancellation(
            List<PrReviewService.InlineComment> inlineComments,
            BooleanSupplier isCancelled,
            Function<PrReviewService.InlineComment, String> commentToPrompt,
            Consumer<String> taskRunner,
            Runnable branchUpdateHook,
            Runnable finalVerificationPass) {

        for (var comment : inlineComments) {
            if (isCancelled.getAsBoolean()) {
                return;
            }

            String prompt = commentToPrompt.apply(comment);

            taskRunner.accept(prompt);

            // Even if cancellation flips during task execution, production semantics still require
            // the per-task branch hook to run for the task that actually executed.
            branchUpdateHook.run();
        }

        if (isCancelled.getAsBoolean()) {
            return;
        }

        finalVerificationPass.run();
    }
}
