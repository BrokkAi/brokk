package ai.brokk.executor.jobs;

import ai.brokk.ContextManager;
import ai.brokk.IConsoleIO;
import ai.brokk.TaskResult;
import ai.brokk.agents.BuildAgent;
import ai.brokk.executor.jobs.PrReviewService;
import ai.brokk.git.GitRepo;
import ai.brokk.tasks.TaskList;
import ai.brokk.util.TextUtil;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Support utilities for ISSUE and ISSUE_WRITER modes.
 */
public final class IssueModeSupport {
    private static final Logger logger = LogManager.getLogger(IssueModeSupport.class);
    private static final String COMMAND_RESULT_EVENT_TYPE = "COMMAND_RESULT";
    private static final int ISSUE_PROMPT_ENRICHMENT_WORD_THRESHOLD = 100;
    private static final Pattern DIFF_FENCE_PATTERN = Pattern.compile("```diff\\R(.*?)(?:\\R)?```", Pattern.DOTALL);

    private IssueModeSupport() {}

    public record CommandResultEvent(
            String stage,
            String command,
            @Nullable Integer attempt,
            boolean skipped,
            @Nullable String skipReason,
            boolean success,
            String output,
            @Nullable String exception) {

        private static final int MAX_OUTPUT_CHARS = 25_000;

        public Map<String, Object> toJson() {
            var data = new LinkedHashMap<String, Object>();
            data.put("stage", stage);
            data.put("command", command);
            if (attempt != null) {
                data.put("attempt", attempt.intValue());
            }
            data.put("skipped", skipped);
            if (skipReason != null && !skipReason.isBlank()) {
                data.put("skipReason", skipReason);
            }
            data.put("success", success);
            if (output.length() > MAX_OUTPUT_CHARS) {
                data.put("output", output.substring(0, MAX_OUTPUT_CHARS));
                data.put("outputTruncated", true);
            } else {
                data.put("output", output);
            }
            if (exception != null && !exception.isBlank()) {
                data.put("exception", exception);
            }
            return data;
        }
    }

    public static CommandResultEvent commandResult(
            String stage,
            @Nullable String command,
            @Nullable Integer attempt,
            boolean skipped,
            @Nullable String skipReason,
            boolean success,
            @Nullable String output,
            @Nullable Throwable exception) {
        String safeCommand = command == null ? "" : command;
        String safeOutput = output == null ? "" : output;

        @Nullable String exceptionText = null;
        if (exception != null) {
            var msg = exception.getMessage();
            exceptionText = (msg == null || msg.isBlank())
                    ? exception.getClass().getSimpleName()
                    : exception.getClass().getSimpleName() + ": " + msg;
        }

        return new CommandResultEvent(
                stage, safeCommand, attempt, skipped, skipReason, success, safeOutput, exceptionText);
    }

    public static void emitCommandResult(
            String jobId, @Nullable JobStore store, @Nullable IConsoleIO io, CommandResultEvent event, String summaryMessage) {
        if (store != null) {
            try {
                store.appendEvent(jobId, JobEvent.of(COMMAND_RESULT_EVENT_TYPE, event.toJson()));
            } catch (IOException e) {
                logger.warn("Failed to append {} event for job {}: {}", COMMAND_RESULT_EVENT_TYPE, jobId, e.getMessage());
            }
        }

        if (!event.success() && io != null) {
            try {
                io.showNotification(IConsoleIO.NotificationRole.INFO, summaryMessage);
            } catch (Throwable ignore) {
                // best-effort
            }
        }
    }

    public static String runAndEmitCommand(
            String jobId,
            @Nullable JobStore store,
            @Nullable IConsoleIO io,
            String stage,
            String command,
            int attempt,
            Function<String, String> commandRunner) {
        try {
            String output = Objects.requireNonNullElse(commandRunner.apply(command), "");
            boolean success = output.isBlank();
            emitCommandResult(
                    jobId,
                    store,
                    io,
                    commandResult(stage, command, attempt, false, null, success, output, null),
                    stage + ": " + (success ? "PASS" : "FAIL"));
            return output;
        } catch (RuntimeException re) {
            emitCommandResult(
                    jobId,
                    store,
                    io,
                    commandResult(stage, command, attempt, false, null, false, "", re),
                    stage + ": ERROR");
            throw re;
        }
    }

    public static void emitSkippedCommand(
            String jobId,
            @Nullable JobStore store,
            @Nullable IConsoleIO io,
            String stage,
            @Nullable String command,
            int attempt,
            String skipReason) {
        emitCommandResult(
                jobId,
                store,
                io,
                commandResult(stage, command, attempt, true, skipReason, true, "", null),
                stage + ": SKIP");
    }

    public static boolean shouldEnrichIssuePrompt(@Nullable String body) {
        return TextUtil.countWords(body) < ISSUE_PROMPT_ENRICHMENT_WORD_THRESHOLD;
    }

    public static String maybeAnnotateDiffBlocks(String bodyMarkdown) {
        if (bodyMarkdown.isBlank() || !bodyMarkdown.contains("```diff")) {
            return bodyMarkdown;
        }

        Matcher matcher = DIFF_FENCE_PATTERN.matcher(bodyMarkdown);
        if (!matcher.find()) {
            return bodyMarkdown;
        }

        matcher.reset();

        var out = new StringBuilder();
        int lastEnd = 0;
        while (matcher.find()) {
            out.append(bodyMarkdown, /* start= */ lastEnd, /* end= */ matcher.start());
            String content = matcher.group(1);
            String annotated = PrReviewService.annotateDiffWithLineNumbers(content);
            out.append("```diff\n").append(annotated).append("\n```");
            lastEnd = matcher.end();
        }
        out.append(bodyMarkdown.substring(lastEnd));
        return out.toString();
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
                    logger.warn("ISSUE job {}: Initial checkout to '{}' failed: {}", jobId, originalBranch, checkoutEx.getMessage());
                    try {
                        var modified = gitRepo.getModifiedFiles();
                        if (!modified.isEmpty()) {
                            gitRepo.createStash("brokk-autostash-for-cleanup");
                            gitRepo.checkout(originalBranch);
                        }
                    } catch (Exception e) {
                        logger.warn("ISSUE job {}: Retry checkout failed: {}", jobId, originalBranch, e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("ISSUE job {}: Unexpected error restoring branch '{}': {}", jobId, originalBranch, e.getMessage());
        }

        try {
            try {
                gitRepo.deleteBranch(issueBranchName);
            } catch (Exception delEx) {
                if (forceDelete) {
                    gitRepo.forceDeleteBranch(issueBranchName);
                } else {
                    logger.info("ISSUE job {}: Leaving branch '{}' for manual inspection", jobId, issueBranchName);
                }
            }
        } catch (Exception e) {
            logger.warn("ISSUE job {}: Failed to delete branch '{}': {}", jobId, issueBranchName, e.getMessage());
        }
    }

    public static void runSingleFixVerificationGate(
            String jobId,
            JobStore store,
            IConsoleIO io,
            @Nullable String verificationCommand,
            Supplier<String> verificationRunner,
            Consumer<String> fixTaskRunner) {
        String commandLabel = verificationCommand == null ? "" : verificationCommand;

        final String firstOut;
        try {
            firstOut = Objects.requireNonNullElse(verificationRunner.get(), "");
        } catch (RuntimeException re) {
            emitCommandResult(jobId, store, io, commandResult("verification", commandLabel, 1, false, null, false, "", re), "Verification: ERROR");
            throw new IssueExecutionException("Verification runner failed: " + re.getMessage(), re);
        }

        boolean passedFirst = firstOut.isBlank();
        emitCommandResult(jobId, store, io, commandResult("verification", commandLabel, 1, false, null, passedFirst, firstOut, null), "Verification: " + (passedFirst ? "PASS" : "FAIL"));

        if (passedFirst) return;

        emitCommandResult(jobId, store, io, commandResult("fix_trigger", commandLabel, 1, false, null, false, firstOut, null), "Fix attempt: TRIGGERED");

        fixTaskRunner.accept("Verification failed. Output:\n" + firstOut + "\n\nPlease make a single fix attempt.");

        final String secondOut;
        try {
            secondOut = Objects.requireNonNullElse(verificationRunner.get(), "");
        } catch (RuntimeException re) {
            emitCommandResult(jobId, store, io, commandResult("verification", commandLabel, 2, false, null, false, "", re), "Verification after fix: ERROR");
            throw new IssueExecutionException("Verification runner failed after fix: " + re.getMessage(), re);
        }

        boolean passedSecond = secondOut.isBlank();
        emitCommandResult(jobId, store, io, commandResult("verification", commandLabel, 2, false, null, passedSecond, secondOut, null), "Verification after fix: " + (passedSecond ? "PASS" : "FAIL"));

        if (!passedSecond) {
            throw new IssueExecutionException("Verification failed after single fix attempt:\n\n" + secondOut);
        }
    }

    public static void runIssueModeTestLintRetryLoop(
            String jobId,
            @Nullable JobStore store,
            @Nullable IConsoleIO io,
            BooleanSupplier isCancelled,
            BiConsumer<Integer, String> progressSink,
            Function<String, String> commandRunner,
            Consumer<String> fixTaskRunner,
            BuildAgent.BuildDetails buildDetailsOverride,
            int maxIterations) {
        if (maxIterations < 1) throw new IllegalArgumentException("maxIterations must be >= 1");

        String testCmd = buildDetailsOverride.testAllCommand();
        String lintCmd = buildDetailsOverride.buildLintCommand();
        boolean testsSkipped = testCmd.isBlank();
        boolean lintSkipped = lintCmd.isBlank();

        @Nullable String lastFailStage = null;
        @Nullable String lastFailCommand = null;
        @Nullable String lastFailOutput = null;

        for (int i = 0; i < maxIterations; i++) {
            int attemptNumber = i + 1;
            if (isCancelled.getAsBoolean()) throw new JobRunner.IssueCancelledException("Cancelled during verification");

            progressSink.accept(attemptNumber, "Final verification attempt %d/%d: tests=%s, lint=%s"
                    .formatted(attemptNumber, maxIterations, testsSkipped ? "SKIP" : "RUN", lintSkipped ? "SKIP" : "RUN"));

            String testOut = "";
            if (testsSkipped) {
                emitSkippedCommand(jobId, store, io, "tests", testCmd, attemptNumber, "blank_command");
            } else {
                testOut = runAndEmitCommand(jobId, store, io, "tests", testCmd, attemptNumber, commandRunner);
            }

            if (!testsSkipped && !testOut.isBlank()) {
                lastFailStage = "tests";
                lastFailCommand = testCmd;
                lastFailOutput = testOut;
                if (!lintSkipped) emitSkippedCommand(jobId, store, io, "lint", lintCmd, attemptNumber, "tests_failed");
                progressSink.accept(attemptNumber, "Final verification attempt %d/%d results: tests=FAIL, lint=SKIP".formatted(attemptNumber, maxIterations));
                fixTaskRunner.accept(testOut);
                continue;
            }

            if (isCancelled.getAsBoolean()) throw new JobRunner.IssueCancelledException("Cancelled during verification");

            String lintOut = "";
            if (lintSkipped) {
                emitSkippedCommand(jobId, store, io, "lint", lintCmd, attemptNumber, "blank_command");
            } else {
                lintOut = runAndEmitCommand(jobId, store, io, "lint", lintCmd, attemptNumber, commandRunner);
            }

            if (!lintSkipped && !lintOut.isBlank()) {
                lastFailStage = "lint";
                lastFailCommand = lintCmd;
                lastFailOutput = lintOut;
                progressSink.accept(attemptNumber, "Final verification attempt %d/%d results: tests=PASS, lint=FAIL".formatted(attemptNumber, maxIterations));
                fixTaskRunner.accept(lintOut);
                continue;
            }

            return;
        }

        String base = "Tests/lint failed after " + maxIterations + " iteration(s)";
        if (lastFailStage != null) {
            throw new IssueExecutionException(base + ". Last failure: stage=" + lastFailStage + ", command=" + lastFailCommand + "\nOutput:\n" + lastFailOutput);
        }
        throw new IssueExecutionException(base);
    }

    public static String buildInlineCommentFixPrompt(PrReviewService.InlineComment comment) {
        return "You are fixing a review inline comment.\n- path: %s\n- line: %d\n- severity: %s\n- body: %s\n\nImplement the fix on the current branch."
                .formatted(
                        comment.path(),
                        comment.line(),
                        comment.severity().name(),
                        comment.bodyMarkdown());
    }

    private static String formatReviewFixOutput(
            PrReviewService.InlineComment comment, String outcome, @Nullable String details) {

        String path = Objects.requireNonNullElse(comment.path(), "");
        String severity = comment.severity().name();
        String body = Objects.requireNonNullElse(comment.bodyMarkdown(), "");

        String base = """
                Finding:
                - path: %s
                - line: %d
                - severity: %s
                - body:
                %s

                Outcome: %s
                """
                .formatted(path, comment.line(), severity, body, outcome)
                .stripIndent();

        if (details == null || details.isBlank()) {
            return base;
        }

        return (base + "\n" + details.strip()).strip();
    }

    public static void runIssueReviewFixAttemptsWithCommandResultEvents(
            String jobId,
            @Nullable JobStore store,
            @Nullable IConsoleIO io,
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

            if (isCancelled.getAsBoolean()) {
                for (int j = i; j < inlineComments.size(); j++) {
                    int skippedAttempt = j + 1;
                    var skippedComment = inlineComments.get(j);
                    String skippedCommand = Objects.requireNonNullElse(skippedComment.path(), "") + ":" + skippedComment.line();

                    emitCommandResult(
                            jobId,
                            store,
                            io,
                            commandResult(
                                    "review_fix",
                                    skippedCommand,
                                    skippedAttempt,
                                    true,
                                    "cancelled",
                                    true,
                                    formatReviewFixOutput(skippedComment, "skipped", "Reason: cancelled"),
                                    null),
                            "review_fix: SKIP");
                }
                return;
            }

            try {
                reviewFixTaskRunner.accept(comment);
                perTaskBranchUpdate.run();

                emitCommandResult(
                        jobId,
                        store,
                        io,
                        commandResult(
                                "review_fix",
                                command,
                                attempt,
                                false,
                                null,
                                true,
                                formatReviewFixOutput(comment, "completed", null),
                                null),
                        "review_fix: PASS");
            } catch (RuntimeException re) {
                String details = re.getMessage() == null ? "Error: RuntimeException" : ("Error: " + re.getMessage());

                emitCommandResult(
                        jobId,
                        store,
                        io,
                        commandResult(
                                "review_fix",
                                command,
                                attempt,
                                false,
                                null,
                                false,
                                formatReviewFixOutput(comment, "failed", details),
                                re),
                        "review_fix: ERROR");
                throw re;
            }
        }
    }
}
