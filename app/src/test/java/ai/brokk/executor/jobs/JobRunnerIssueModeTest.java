package ai.brokk.executor.jobs;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.agents.BuildAgent;
import ai.brokk.testutil.TestGitRepo;
import ai.brokk.util.Json;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
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
        // Verify parseMode correctly identifies ISSUE mode from tags
        JobSpec issueSpec = JobSpec.ofIssue(
                "gpt-4", null, "fake-token", "owner", "repo", 42, Json.toJson(BuildAgent.BuildDetails.EMPTY));

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
        assertEquals(5, spec.maxIssueFixAttempts());
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
        assertEquals(7, persistedSpec.maxIssueFixAttempts());

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
        assertEquals(JobSpec.DEFAULT_MAX_ISSUE_FIX_ATTEMPTS, persistedSpec.maxIssueFixAttempts());
    }
}
