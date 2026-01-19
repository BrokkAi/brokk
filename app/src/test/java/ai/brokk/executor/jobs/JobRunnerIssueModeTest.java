package ai.brokk.executor.jobs;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.agents.BuildAgent;
import ai.brokk.testutil.TestConsoleIO;
import ai.brokk.testutil.TestGitRepo;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
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
    void testRunFinalGateRetryLoop_exhaustsAttempts_andUsesFinalTerminology() {
        var io = new TestConsoleIO();
        // force commandRunner to always return failure output
        java.util.function.Function<String, String> commandRunner = cmd -> "failing output";
        java.util.function.Consumer<String> fixRunner = prompt -> {
            // no-op: do not fix anything
        };

        // Build minimal BuildDetails with non-blank commands to ensure both test and lint run
        BuildAgent.BuildDetails buildDetails =
                new BuildAgent.BuildDetails("./gradlew classes", "./gradlew test", "./gradlew test --tests", Set.of());

        // attemptsLeft = 2 should cause two attempts then throw
        java.util.concurrent.atomic.AtomicInteger attemptsLeft = new java.util.concurrent.atomic.AtomicInteger(2);

        IssueExecutionException ex = assertThrows(
                IssueExecutionException.class,
                () -> JobRunner.runPrePrGateRetryLoop(
                        "job-final-gate-1", store, io, buildDetails, attemptsLeft, commandRunner, fixRunner));

        String msg = ex.getMessage();
        assertTrue(
                msg.contains("Final gate failed after")
                        || msg.contains("Pre-PR gate failed after")
                        || msg.contains("Pre-PR gate failed"),
                "Exception message should indicate final/pre-PR gate failure (kept for compatibility): " + msg);

        // Ensure the message does not use legacy "pre-pr" casing variants (case-insensitive match of 'pre-pr' or
        // 'prepr')
        assertFalse(
                msg.toLowerCase().contains("pre-pr") || msg.toLowerCase().contains("prepr"),
                "Exception message must not contain pre-PR terminology: " + msg);
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
}
