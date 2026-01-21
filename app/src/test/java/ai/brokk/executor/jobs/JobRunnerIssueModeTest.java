package ai.brokk.executor.jobs;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.agents.BuildAgent;
import ai.brokk.testutil.TestConsoleIO;
import ai.brokk.testutil.TestGitRepo;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for ISSUE mode in JobRunner.
 *
 * <p>Note: Full integration testing of JobRunner requires the concrete ContextManager class,
 * which has methods not exposed in IContextManager (setIo, submitLlmAction, executeTask, etc.).
 * These tests focus on the supporting components that can be tested in isolation.
 */
class JobRunnerIssueModeTest {

    @TempDir
    Path tempDir;

    private TestGitRepo repo;
    private JobStore store;

    @BeforeEach
    void setUp() throws Exception {
        Path projectRoot = tempDir.resolve("project");
        Path worktreeDir = tempDir.resolve("worktrees");
        Path storeDir = tempDir.resolve("store");
        Files.createDirectories(projectRoot);
        Files.createDirectories(worktreeDir);
        Files.createDirectories(storeDir);

        // Initialize git repo with one commit
        try (Git git = Git.init().setDirectory(projectRoot.toFile()).call()) {
            Files.writeString(projectRoot.resolve("README.md"), "initial");
            git.add().addFilepattern("README.md").call();
            git.commit().setMessage("Initial commit").setSign(false).call();
        }

        repo = new TestGitRepo(projectRoot, worktreeDir);
        store = new JobStore(storeDir);
    }

    @Test
    void testIssueModeParseMode() {
        // Add mode tag to verify parsing
        JobSpec specWithMode = JobSpec.of(
                "",
                false,
                false,
                "gpt-4",
                null,
                null,
                false,
                Map.of(
                        "mode", "ISSUE",
                        "github_token", "fake-token",
                        "repo_owner", "owner",
                        "repo_name", "repo",
                        "issue_number", "42"),
                (String) null);

        assertEquals(JobRunner.Mode.ISSUE, JobRunner.parseMode(specWithMode));
    }

    @Test
    void testIssueWriterModeParseMode_isCaseInsensitive() {
        JobSpec specUpper = JobSpec.of(
                "task", false, false, "gpt-4", null, null, false, Map.of("mode", "ISSUE_WRITER"), (String) null);
        assertEquals(JobRunner.Mode.ISSUE_WRITER, JobRunner.parseMode(specUpper));

        JobSpec specLower = JobSpec.of(
                "task", false, false, "gpt-4", null, null, false, Map.of("mode", "issue_writer"), (String) null);
        assertEquals(JobRunner.Mode.ISSUE_WRITER, JobRunner.parseMode(specLower));
    }

    @Test
    void testParseMode_fallsBackToArchitect_onMissingBlankOrInvalidMode() {
        JobSpec missingMode =
                JobSpec.of("task", false, false, "gpt-4", null, null, false, Map.of("x", "y"), (String) null);
        assertEquals(JobRunner.Mode.ARCHITECT, JobRunner.parseMode(missingMode));

        JobSpec blankMode =
                JobSpec.of("task", false, false, "gpt-4", null, null, false, Map.of("mode", "   "), (String) null);
        assertEquals(JobRunner.Mode.ARCHITECT, JobRunner.parseMode(blankMode));

        JobSpec invalidMode =
                JobSpec.of("task", false, false, "gpt-4", null, null, false, Map.of("mode", "NOPE"), (String) null);
        assertEquals(JobRunner.Mode.ARCHITECT, JobRunner.parseMode(invalidMode));
    }

    @Test
    void testIssueDeliveryPolicy_DefaultsToPr() {
        JobSpec spec = JobSpec.ofIssue("gpt-4", null, "token", "owner", "repo", 1, "{}");
        assertTrue(JobRunner.issueDeliveryEnabled(spec), "Default policy should enable PR creation");
    }

    @Test
    void testIssueDeliveryPolicy_DisableViaTag() {
        JobSpec spec = new JobSpec(
                "",
                false,
                false,
                "gpt-4",
                null,
                null,
                false,
                Map.of(
                        "mode", "ISSUE",
                        "github_token", "token",
                        "repo_owner", "owner",
                        "repo_name", "repo",
                        "issue_number", "2",
                        "issue_delivery", "none"),
                null,
                null,
                null,
                null,
                null,
                null,
                JobSpec.DEFAULT_MAX_ISSUE_FIX_ATTEMPTS);
        assertFalse(JobRunner.issueDeliveryEnabled(spec), "issue_delivery=none should disable PR creation");
    }

    @Test
    void testJobSpecOfIssueCreatesCorrectTags() {
        JobSpec spec = JobSpec.ofIssue(
                "gpt-4",
                "gpt-4-mini",
                "my-token",
                "myowner",
                "myrepo",
                123,
                "{\"buildLintCommand\":\"./gradlew build\"}");

        assertEquals("my-token", spec.getGithubToken());
        assertEquals("myowner", spec.getRepoOwner());
        assertEquals("myrepo", spec.getRepoName());
        assertEquals(Integer.valueOf(123), spec.getIssueNumber());
        assertEquals("{\"buildLintCommand\":\"./gradlew build\"}", spec.getBuildSettingsJson());
        assertEquals("gpt-4", spec.plannerModel());
        assertEquals("gpt-4-mini", spec.codeModel());
        assertEquals(5, spec.effectiveMaxIssueFixAttempts());
    }

    @Test
    void testIssueBranchNameGeneration() throws Exception {
        // Verify branch name generation for issues
        String branchName = IssueService.generateBranchName(42, repo);
        assertEquals("brokk/issue-42", branchName);

        // Create the branch and verify collision handling
        repo.getGit().branchCreate().setName("brokk/issue-42").call();
        repo.invalidateCaches();

        String secondBranchName = IssueService.generateBranchName(42, repo);
        assertEquals("brokk/issue-42-2", secondBranchName);
    }

    @Test
    void testParseBuildSettings() {
        // Test parsing of build settings JSON
        String json =
                """
                {
                    "buildLintCommand": "./gradlew classes",
                    "testAllCommand": "./gradlew test",
                    "testSomeCommand": "./gradlew test --tests"
                }
                """;

        BuildAgent.BuildDetails details = IssueService.parseBuildSettings(json);
        assertEquals("./gradlew classes", details.buildLintCommand());
        assertEquals("./gradlew test", details.testAllCommand());
        assertEquals("./gradlew test --tests", details.testSomeCommand());
    }

    @Test
    void testParseBuildSettingsEmpty() {
        // Null or blank input should return EMPTY
        assertEquals(BuildAgent.BuildDetails.EMPTY, IssueService.parseBuildSettings(null));
        assertEquals(BuildAgent.BuildDetails.EMPTY, IssueService.parseBuildSettings(""));
        assertEquals(BuildAgent.BuildDetails.EMPTY, IssueService.parseBuildSettings("   "));
    }

    @Test
    void testBranchCreationAndCheckout() throws Exception {
        String branchName = IssueService.generateBranchName(99, repo);
        assertEquals("brokk/issue-99", branchName);

        // Verify we can create and checkout this branch
        repo.createAndCheckoutBranch(branchName, "HEAD");
        assertEquals(branchName, repo.getCurrentBranch());
    }

    @Test
    void testJobStoreCreatesIssueJob() throws Exception {
        JobSpec spec = JobSpec.ofIssue("gpt-4", null, "token", "owner", "repo", 42, "{}", 7);

        String idempotencyKey = "issue-job-test";
        var result = store.createOrGetJob(idempotencyKey, spec);

        assertTrue(result.isNewJob());
        // The jobId is a generated UUID, not the idempotency key
        assertNotNull(result.jobId());
        assertFalse(result.jobId().isBlank());

        // Verify job can be loaded using the returned jobId
        JobStatus status = store.loadStatus(result.jobId());
        assertNotNull(status);
        assertEquals("QUEUED", status.state());

        JobSpec persistedSpec = store.loadSpec(result.jobId());
        assertNotNull(persistedSpec);
        assertEquals(7, persistedSpec.effectiveMaxIssueFixAttempts());

        // Verify idempotency: same key returns same job
        var secondResult = store.createOrGetJob(idempotencyKey, spec);
        assertFalse(secondResult.isNewJob());
        assertEquals(result.jobId(), secondResult.jobId());
    }

    @Test
    void testJobStorePersistsDefaultMaxIssueFixAttempts() throws Exception {
        JobSpec spec = JobSpec.ofIssue("gpt-4", null, "token", "owner", "repo", 42, "{}");

        var result = store.createOrGetJob("issue-job-default-attempts", spec);

        JobSpec persistedSpec = store.loadSpec(result.jobId());
        assertNotNull(persistedSpec);
        assertEquals(JobSpec.DEFAULT_MAX_ISSUE_FIX_ATTEMPTS, persistedSpec.effectiveMaxIssueFixAttempts());
    }

    @Test
    void testSingleFixVerificationBehavior() {
        var verificationCalls = new AtomicInteger(0);
        var fixCalls = new AtomicInteger(0);
        var io = new TestConsoleIO();

        java.util.function.Supplier<String> verificationRunner = () -> {
            int c = verificationCalls.incrementAndGet();
            // First verification fails, second verification also fails to exercise exception path.
            return c == 1 ? "initial failure" : "still failing";
        };
        java.util.function.Consumer<String> fixRunner = prompt -> fixCalls.incrementAndGet();

        IssueExecutionException ex = assertThrows(
                IssueExecutionException.class,
                () -> JobRunner.runSingleFixVerificationGate(
                        "job-single-fix-1", store, io, verificationRunner, fixRunner));

        assertEquals(2, verificationCalls.get(), "Verification should be called exactly twice");
        assertEquals(1, fixCalls.get(), "Fix runner should be called exactly once");
        assertTrue(ex.getMessage().contains("Verification failed after single fix attempt"));
    }

    @Test
    void issueModeTestLintRetryLoop_testFailure_triggersFixTaskWithExactPrefix_andSkipsLintThatIteration() {
        var cancelled = new AtomicBoolean(false);

        var calls = new ArrayList<String>();
        var prompts = new ArrayList<String>();

        java.util.function.Function<String, String> commandRunner = cmd -> {
            calls.add(cmd);
            return "TEST FAILED OUTPUT";
        };

        java.util.function.Consumer<String> fixTaskRunner = out -> prompts.add("fix this build error:\n" + out);

        assertThrows(
                IssueExecutionException.class,
                () -> JobRunner.runIssueModeTestLintRetryLoop(
                        cancelled::get,
                        commandRunner,
                        fixTaskRunner,
                        new BuildAgent.BuildDetails("./gradlew lint", "./gradlew test", "", java.util.Set.of()),
                        2));

        assertEquals(List.of("./gradlew test", "./gradlew test"), calls, "Lint must be skipped when tests fail");
        assertEquals(2, prompts.size());
        assertTrue(prompts.get(0).startsWith("fix this build error:"), "Prefix must be exact and first");
        assertEquals("fix this build error:\nTEST FAILED OUTPUT", prompts.get(0));
    }

    @Test
    void issueModeTestLintRetryLoop_lintFailure_triggersFixTaskWithExactPrefix() {
        var cancelled = new AtomicBoolean(false);

        var calls = new ArrayList<String>();
        var fixPrompts = new ArrayList<String>();

        java.util.function.Function<String, String> commandRunner = cmd -> {
            calls.add(cmd);
            if (calls.size() % 2 == 1) {
                return "";
            }
            return "LINT FAILED OUTPUT";
        };

        java.util.function.Consumer<String> fixTaskRunner = out -> fixPrompts.add("fix this build error:\n" + out);

        assertThrows(
                IssueExecutionException.class,
                () -> JobRunner.runIssueModeTestLintRetryLoop(
                        cancelled::get,
                        commandRunner,
                        fixTaskRunner,
                        new BuildAgent.BuildDetails("./gradlew lint", "./gradlew test", "", java.util.Set.of()),
                        2));

        assertEquals(List.of("./gradlew test", "./gradlew lint", "./gradlew test", "./gradlew lint"), calls);
        assertEquals(2, fixPrompts.size());
        assertEquals("fix this build error:\nLINT FAILED OUTPUT", fixPrompts.get(0));
    }

    @Test
    void issueModeTestLintRetryLoop_exitsEarlyWhenBothPass() {
        var cancelled = new AtomicBoolean(false);

        var calls = new ArrayList<String>();
        var fixCalls = new AtomicInteger(0);

        java.util.function.Function<String, String> commandRunner = cmd -> {
            calls.add(cmd);
            return "";
        };

        java.util.function.Consumer<String> fixTaskRunner = out -> fixCalls.incrementAndGet();

        JobRunner.runIssueModeTestLintRetryLoop(
                cancelled::get,
                commandRunner,
                fixTaskRunner,
                new BuildAgent.BuildDetails("./gradlew lint", "./gradlew test", "", java.util.Set.of()),
                20);

        assertEquals(List.of("./gradlew test", "./gradlew lint"), calls, "Should run tests then lint once");
        assertEquals(0, fixCalls.get(), "No fix tasks when both pass");
    }

    @Test
    void issueModeTestLintRetryLoop_throwsAfter20IterationsIfNeverSucceeds() {
        var cancelled = new AtomicBoolean(false);

        var fixCalls = new AtomicInteger(0);
        var testCalls = new AtomicInteger(0);

        java.util.function.Function<String, String> commandRunner = cmd -> {
            testCalls.incrementAndGet();
            return "always failing";
        };

        java.util.function.Consumer<String> fixTaskRunner = out -> fixCalls.incrementAndGet();

        IssueExecutionException ex = assertThrows(
                IssueExecutionException.class,
                () -> JobRunner.runIssueModeTestLintRetryLoop(
                        cancelled::get,
                        commandRunner,
                        fixTaskRunner,
                        new BuildAgent.BuildDetails("./gradlew lint", "./gradlew test", "", java.util.Set.of()),
                        20));

        assertEquals(20, testCalls.get(), "Must run exactly 20 iterations");
        assertEquals(20, fixCalls.get(), "Must perform exactly one fix per iteration");
        assertTrue(ex.getMessage().contains("Tests/lint failed after 20 iteration(s)"));
    }

    @Test
    void issueReviewTaskSequence_convertsCommentsToPrompts_andRunsInOrder_andCallsBranchHook_andFinalVerificationAfter()
            throws Exception {
        var comments = List.of(
                new PrReviewService.InlineComment("src/A.java", 10, "First issue", PrReviewService.Severity.HIGH),
                new PrReviewService.InlineComment("src/B.java", 20, "Second issue", PrReviewService.Severity.CRITICAL),
                new PrReviewService.InlineComment("src/C.java", 30, "Third issue", PrReviewService.Severity.HIGH));

        var observed = new ArrayList<String>();
        var prompts = new ArrayList<String>();
        var taskIndex = new AtomicInteger(0);
        var branchHookCalls = new AtomicInteger(0);

        var currentPhase = new AtomicReference<String>("START");

        java.util.function.Function<PrReviewService.InlineComment, String> commentToPrompt = c -> {
            String prompt = JobRunner.buildInlineCommentFixPrompt(c);
            prompts.add(prompt);
            return prompt;
        };

        java.util.function.Consumer<String> taskRunner = prompt -> {
            assertEquals("TASKS", currentPhase.get(), "Tasks must run during TASKS phase");
            int idx = taskIndex.incrementAndGet();
            observed.add("task-" + idx);
            observed.add("prompt-" + idx + ":" + prompt);
        };

        Runnable branchUpdateHook = () -> {
            assertEquals("TASKS", currentPhase.get(), "Branch update hook must run during TASKS phase");
            int idx = branchHookCalls.incrementAndGet();
            observed.add("branchHook-" + idx);
        };

        Runnable finalVerification = () -> {
            assertEquals("FINAL_VERIFICATION", currentPhase.get(), "Final verification must run after tasks");
            observed.add("finalVerification");
        };

        currentPhase.set("TASKS");
        JobRunner.runIssueReviewTaskSequence(comments, commentToPrompt, taskRunner, branchUpdateHook, () -> {
            currentPhase.set("FINAL_VERIFICATION");
            finalVerification.run();
        });

        assertEquals(3, prompts.size(), "Each inline comment must be converted to a prompt");
        assertTrue(prompts.get(0).contains("src/A.java"));
        assertTrue(prompts.get(0).contains("line: 10"));
        assertTrue(prompts.get(0).contains("First issue"));

        assertTrue(prompts.get(1).contains("src/B.java"));
        assertTrue(prompts.get(1).contains("line: 20"));
        assertTrue(prompts.get(1).contains("Second issue"));

        assertTrue(prompts.get(2).contains("src/C.java"));
        assertTrue(prompts.get(2).contains("line: 30"));
        assertTrue(prompts.get(2).contains("Third issue"));

        assertEquals(3, taskIndex.get(), "Tasks must execute exactly once per comment");
        assertEquals(3, branchHookCalls.get(), "Branch update hook must be called once per task");

        assertEquals(
                List.of(
                        "task-1",
                        "prompt-1:" + prompts.get(0),
                        "branchHook-1",
                        "task-2",
                        "prompt-2:" + prompts.get(1),
                        "branchHook-2",
                        "task-3",
                        "prompt-3:" + prompts.get(2),
                        "branchHook-3",
                        "finalVerification"),
                observed,
                "Sequence must be strictly serial and ordered: task -> branchHook after each task -> final verification");
    }

    @Test
    void
            issueReviewTaskSequence_productionWiring_shortCircuitsOnCancellation_skipsRemainingTasksAndFinalVerification() {
        var comments = List.of(
                new PrReviewService.InlineComment("src/A.java", 10, "First issue", PrReviewService.Severity.HIGH),
                new PrReviewService.InlineComment("src/B.java", 20, "Second issue", PrReviewService.Severity.CRITICAL),
                new PrReviewService.InlineComment("src/C.java", 30, "Third issue", PrReviewService.Severity.HIGH));

        var cancelled = new AtomicBoolean(false);

        var promptsBuilt = new AtomicInteger(0);
        var tasksRun = new AtomicInteger(0);
        var branchHooks = new AtomicInteger(0);
        var finalVerificationCalls = new AtomicInteger(0);

        var observed = new ArrayList<String>();

        java.util.function.Function<PrReviewService.InlineComment, String> commentToPrompt = c -> {
            promptsBuilt.incrementAndGet();
            return JobRunner.buildInlineCommentFixPrompt(c);
        };

        java.util.function.Consumer<String> taskRunner = prompt -> {
            int idx = tasksRun.incrementAndGet();
            observed.add("task-" + idx);
            if (idx == 1) {
                cancelled.set(true);
            }
        };

        Runnable branchUpdateHook = () -> {
            branchHooks.incrementAndGet();
            observed.add("branchHook-" + branchHooks.get());
        };

        Runnable finalVerification = () -> {
            finalVerificationCalls.incrementAndGet();
            observed.add("finalVerification");
        };

        JobRunner.runIssueReviewTaskSequenceWithCancellation(
                comments, cancelled::get, commentToPrompt, taskRunner, branchUpdateHook, finalVerification);

        assertTrue(cancelled.get(), "Test must trigger cancellation");
        assertEquals(1, tasksRun.get(), "Only the first task should run after cancellation triggers");
        assertEquals(1, branchHooks.get(), "Branch update hook must run only for executed tasks");
        assertEquals(0, finalVerificationCalls.get(), "Final verification must be skipped when cancellation is active");

        assertEquals(List.of("task-1", "branchHook-1"), observed);

        assertEquals(
                1,
                promptsBuilt.get(),
                "Production wiring should avoid building prompts for comments that will be skipped due to cancellation");
    }

    @Test
    void issueReviewTaskSequence_noComments_stillRunsFinalVerification() {
        var observed = new ArrayList<String>();
        var branchHookCalls = new AtomicInteger(0);

        JobRunner.runIssueReviewTaskSequence(
                List.of(),
                JobRunner::buildInlineCommentFixPrompt,
                prompt -> observed.add("task:" + prompt),
                () -> branchHookCalls.incrementAndGet(),
                () -> observed.add("finalVerification"));

        assertTrue(observed.contains("finalVerification"));
        assertEquals(1, observed.size(), "No tasks should run when comments list is empty");
        assertEquals(0, branchHookCalls.get(), "Branch update hook must not run when there are no tasks");
    }

    @Test
    void testPrSkippedWhenFinalVerificationStillFails() {
        var verificationCalls = new AtomicInteger(0);
        var fixCalls = new AtomicInteger(0);
        var io = new TestConsoleIO();
        var prCreated = new AtomicBoolean(false);

        java.util.function.Supplier<String> verificationRunner = () -> {
            verificationCalls.incrementAndGet();
            return "still failing";
        };
        java.util.function.Consumer<String> fixRunner = prompt -> fixCalls.incrementAndGet();

        assertThrows(IssueExecutionException.class, () -> {
            JobRunner.runSingleFixVerificationGate("job-pr-skip-1", store, io, verificationRunner, fixRunner);
            // This line simulates PR creation that must not be reached if verification fails.
            prCreated.set(true);
        });

        assertFalse(prCreated.get(), "PR creation path must not be reached when verification fails");
        assertEquals(2, verificationCalls.get(), "Verification should be invoked twice (before and after fix)");
        assertEquals(1, fixCalls.get(), "Exactly one fix attempt should be invoked");
    }

    // Keep cleanup tests but update names/expectations to match simplified semantics.
    @Test
    void cleanupIssueBranch_restoresOriginalBranch_andDeletesIssueBranch() throws Exception {
        String originalBranch = repo.getCurrentBranch();

        String issueBranchName = "brokk/issue-cleanup-1";
        repo.createAndCheckoutBranch(issueBranchName, originalBranch);
        assertEquals(issueBranchName, repo.getCurrentBranch());

        assertDoesNotThrow(
                () -> JobRunner.cleanupIssueBranch("job-cleanup-1", repo, originalBranch, issueBranchName, false));

        assertEquals(originalBranch, repo.getCurrentBranch());
        assertFalse(repo.isLocalBranch(issueBranchName), "Issue branch should be deleted after cleanup");
    }

    @Test
    void cleanupIssueBranch_forceDeleteFallback_deletesBranch() throws Exception {
        String originalBranch = repo.getCurrentBranch();
        Path root = repo.getWorkTreeRoot();

        String issueBranchName = "brokk/issue-cleanup-3";
        repo.createAndCheckoutBranch(issueBranchName, originalBranch);

        Files.writeString(root.resolve("README.md"), "unique commit");
        repo.getGit().add().addFilepattern("README.md").call();
        repo.getGit().commit().setMessage("unique").setSign(false).call();

        assertDoesNotThrow(
                () -> JobRunner.cleanupIssueBranch("job-cleanup-3", repo, originalBranch, issueBranchName, true));

        assertEquals(originalBranch, repo.getCurrentBranch());
        assertFalse(
                repo.isLocalBranch(issueBranchName), "Cleanup should delete issue branch even when forcing fallback");
    }

    @Test
    void cleanupIssueBranch_returnsToOriginalBranch_andDeletesBranch_whenNoUniqueCommits() throws Exception {
        String originalBranch = repo.getCurrentBranch();

        String issueBranchName = "brokk/issue-cleanup-1";
        repo.createAndCheckoutBranch(issueBranchName, originalBranch);
        assertEquals(issueBranchName, repo.getCurrentBranch());

        assertDoesNotThrow(
                () -> JobRunner.cleanupIssueBranch("job-cleanup-1", repo, originalBranch, issueBranchName, false));

        assertEquals(originalBranch, repo.getCurrentBranch());
        assertFalse(
                repo.isLocalBranch(issueBranchName), "Issue branch should be deleted when it has no unique commits");
    }

    @Test
    void cleanupIssueBranch_stashesAndReturnsToOriginalBranch_whenCheckoutBlockedByLocalChanges() throws Exception {
        String originalBranch = repo.getCurrentBranch();
        Path root = repo.getWorkTreeRoot();

        String issueBranchName = "brokk/issue-cleanup-2";
        repo.createAndCheckoutBranch(issueBranchName, originalBranch);

        // Create a unique commit on issue branch.
        Files.writeString(root.resolve("README.md"), "issue change");
        repo.getGit().add().addFilepattern("README.md").call();
        repo.getGit().commit().setMessage("issue commit").setSign(false).call();

        // Now create an uncommitted change that would be overwritten by checkout.
        int stashesBefore = repo.listStashes().size();
        Files.writeString(root.resolve("README.md"), "uncommitted change");

        assertDoesNotThrow(
                () -> JobRunner.cleanupIssueBranch("job-cleanup-2", repo, originalBranch, issueBranchName, false));

        assertEquals(originalBranch, repo.getCurrentBranch());
        assertTrue(repo.listStashes().size() > stashesBefore, "Cleanup should create a stash when checkout is blocked");
    }

    @Test
    void cleanupIssueBranch_forceDelete_deletesBranchEvenWithUniqueCommits() throws Exception {
        String originalBranch = repo.getCurrentBranch();
        Path root = repo.getWorkTreeRoot();

        String issueBranchName = "brokk/issue-cleanup-3";
        repo.createAndCheckoutBranch(issueBranchName, originalBranch);

        Files.writeString(root.resolve("README.md"), "unique commit");
        repo.getGit().add().addFilepattern("README.md").call();
        repo.getGit().commit().setMessage("unique").setSign(false).call();

        assertDoesNotThrow(
                () -> JobRunner.cleanupIssueBranch("job-cleanup-3", repo, originalBranch, issueBranchName, true));

        assertEquals(originalBranch, repo.getCurrentBranch());
        assertFalse(repo.isLocalBranch(issueBranchName), "Force-delete cleanup should delete issue branch");
    }

    @Test
    void cleanupIssueBranch_respectsForceDeleteFlag_falseLeavesBranchWhenNormalDeleteFails() throws Exception {
        // Setup: create a branch and make it unmerged so normal delete may fail (JGit throws when branch not fully
        // merged).
        String originalBranch = repo.getCurrentBranch();
        Path root = repo.getWorkTreeRoot();

        String issueBranchName = "brokk/issue-cleanup-force-flag-1";
        repo.createAndCheckoutBranch(issueBranchName, originalBranch);

        // Create a unique commit on issue branch so it is not fully merged into originalBranch.
        Files.writeString(root.resolve("README.md"), "unique commit for force-flag test");
        repo.getGit().add().addFilepattern("README.md").call();
        repo.getGit()
                .commit()
                .setMessage("unique-for-force-flag")
                .setSign(false)
                .call();

        // Return to original branch so cleanup will attempt to delete issue branch
        repo.checkout(originalBranch);
        assertEquals(originalBranch, repo.getCurrentBranch());

        // Call cleanup with forceDelete=false. If deleteBranch throws, cleanup should NOT force-delete and branch
        // should remain.
        JobRunner.cleanupIssueBranch("job-cleanup-force-flag-1", repo, originalBranch, issueBranchName, false);

        // The branch should still exist (delete should not have been force-applied).
        assertTrue(
                repo.isLocalBranch(issueBranchName),
                "Issue branch must remain when forceDelete=false and normal delete fails");
    }

    @Test
    void cleanupIssueBranch_respectsForceDeleteFlag_trueRemovesBranchWhenNormalDeleteFails() throws Exception {
        // Setup: create a branch and make it unmerged so normal delete may fail.
        String originalBranch = repo.getCurrentBranch();
        Path root = repo.getWorkTreeRoot();

        String issueBranchName = "brokk/issue-cleanup-force-flag-2";
        repo.createAndCheckoutBranch(issueBranchName, originalBranch);

        // Create a unique commit on issue branch so it is not fully merged into originalBranch.
        Files.writeString(root.resolve("README.md"), "unique commit for force-flag test 2");
        repo.getGit().add().addFilepattern("README.md").call();
        repo.getGit()
                .commit()
                .setMessage("unique-for-force-flag-2")
                .setSign(false)
                .call();

        // Return to original branch so cleanup will attempt to delete issue branch
        repo.checkout(originalBranch);
        assertEquals(originalBranch, repo.getCurrentBranch());

        // Call cleanup with forceDelete=true; branch should be removed even if normal delete would have failed.
        JobRunner.cleanupIssueBranch("job-cleanup-force-flag-2", repo, originalBranch, issueBranchName, true);

        assertFalse(
                repo.isLocalBranch(issueBranchName),
                "Issue branch must be removed when forceDelete=true and normal delete fails");
    }

    @Test
    void issueModeComputeInlineComments_emptyDiff_returnsEmptyList() throws Exception {
        // Precondition (optional): when the base branch and HEAD point to the same commit, the unified diff is empty.
        String baseBranch = repo.getCurrentBranch();

        String diff = PrReviewService.computePrDiff(repo, baseBranch, "HEAD");
        assertTrue(diff.isBlank(), "Precondition: diff must be empty when baseBranch == HEAD");

        var reviewCalls = new AtomicInteger(0);

        var comments = JobRunner.issueModeComputeInlineCommentsOrEmpty(() -> "", ignoredDiff -> {
            reviewCalls.incrementAndGet();
            return List.of();
        });

        assertTrue(comments.isEmpty(), "Empty diff must short-circuit to no inline comments");
        assertEquals(0, reviewCalls.get(), "Review callback must not be invoked when diff is blank");
    }
}
