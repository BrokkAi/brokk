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
    void testPrePrGateExhaustsAttemptsBlocksPrCreation() {
        var prCreated = new AtomicBoolean(false);
        var fixCalls = new AtomicInteger(0);

        var io = new TestConsoleIO();
        var details = new BuildAgent.BuildDetails("./lint", "./testAll", "", Set.of());

        // maxAttempts = 3 -> fix called maxAttempts - 1 = 2 times
        assertThrows(IssueExecutionException.class, () -> {
            JobRunner.runPrePrGateRetryLoop(
                    "job-1",
                    store,
                    io,
                    details,
                    3,
                    cmd -> cmd.contains("testAll") ? "tests failed" : "lint failed",
                    prompt -> fixCalls.incrementAndGet());
            prCreated.set(true);
        });

        assertFalse(prCreated.get());
        assertEquals(2, fixCalls.get());
    }

    @Test
    void testPrePrGateSucceedsAfterFixAllowsPrCreation() {
        var prCreated = new AtomicBoolean(false);
        var fixCalls = new AtomicInteger(0);

        var io = new TestConsoleIO();
        var details = new BuildAgent.BuildDetails("./lint", "./testAll", "", Set.of());

        var testCmdCalls = new AtomicInteger(0);

        assertDoesNotThrow(() -> {
            JobRunner.runPrePrGateRetryLoop(
                    "job-2",
                    store,
                    io,
                    details,
                    5,
                    cmd -> {
                        if (cmd.contains("testAll")) {
                            int attempt = testCmdCalls.incrementAndGet();
                            return attempt == 1 ? "tests failed on attempt 1" : "";
                        }
                        return "";
                    },
                    prompt -> fixCalls.incrementAndGet());
            prCreated.set(true);
        });

        assertTrue(prCreated.get());
        assertEquals(1, fixCalls.get());
    }

    @Test
    void testPrePrGateBlankCommandsAreSkippedAndPassing() {
        var fixCalls = new AtomicInteger(0);

        var io = new TestConsoleIO();
        var details = new BuildAgent.BuildDetails("", "", "", Set.of());

        assertDoesNotThrow(() -> JobRunner.runPrePrGateRetryLoop(
                "job-blank-cmds",
                store,
                io,
                details,
                3,
                cmd -> fail("commandRunner should not be invoked when commands are blank: " + cmd),
                prompt -> fixCalls.incrementAndGet()));

        assertEquals(0, fixCalls.get());
    }

    @Test
    void testSharedAttemptBudgetAcrossVerificationAndPrePrGate() {
        /*
         * Simulate a shared budget across per-task verification and pre-PR gate.
         * We'll exercise the helpers directly to validate budget accounting.
         */
        var fixCalls = new AtomicInteger(0);
        var io = new TestConsoleIO();

        var sharedAttempts = new java.util.concurrent.atomic.AtomicInteger(3);

        // Verification runner: first call fails, second call passes.
        var verificationCalls = new AtomicInteger(0);
        java.util.function.Supplier<String> verificationRunner = () -> {
            int c = verificationCalls.incrementAndGet();
            return c == 1 ? "verification failed once" : "";
        };

        java.util.function.Consumer<String> verificationFix = prompt -> fixCalls.incrementAndGet();

        // Run verification with per-task cap of 2 (but sharing the 3-attempt budget)
        var perTaskAttempts = new java.util.concurrent.atomic.AtomicInteger(Math.min(sharedAttempts.get(), 2));
        assertDoesNotThrow(() -> JobRunner.runVerificationRetryLoop(
                "job-shared-1",
                store,
                io,
                perTaskAttempts,
                verificationRunner,
                verificationFix));

        int consumedPerTask = 2 - perTaskAttempts.get();
        sharedAttempts.addAndGet(-consumedPerTask);

        assertEquals(1, fixCalls.get(), "One fix should have been invoked during per-task verification");
        assertEquals(2, sharedAttempts.get(), "Two attempts should remain in shared budget");

        // Now run pre-PR gate which will fail twice and exhaust remaining attempts
        var details = new BuildAgent.BuildDetails("./lint", "./testAll", "", Set.of());
        var testCmdCalls = new AtomicInteger(0);

        java.util.function.Function<String, String> commandRunner = cmd -> {
            if (cmd.contains("testAll")) {
                int attempt = testCmdCalls.incrementAndGet();
                // Fail twice to exhaust remaining attempts
                return attempt <= 2 ? "tests failed" : "";
            }
            return "";
        };

        java.util.function.Consumer<String> prePrFix = prompt -> fixCalls.incrementAndGet();

        assertThrows(IssueExecutionException.class, () -> JobRunner.runPrePrGateRetryLoop(
                "job-shared-2", store, io, details, sharedAttempts, commandRunner, prePrFix));

        assertEquals(2, fixCalls.get(), "Total fixes across verification and pre-PR gate should equal 2");
        assertEquals(0, sharedAttempts.get(), "Shared attempts should be exhausted");
    }
}
