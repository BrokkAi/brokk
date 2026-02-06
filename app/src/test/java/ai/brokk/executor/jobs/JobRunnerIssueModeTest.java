package ai.brokk.executor.jobs;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.agents.BuildAgent.BuildDetails;
import ai.brokk.testutil.TestConsoleIO;
import ai.brokk.testutil.TestGitRepo;
import ai.brokk.testutil.TestProject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
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
    void testShouldEnrichIssuePrompt() {
        assertTrue(IssueWriterService.shouldEnrichIssuePrompt(null));
        assertTrue(IssueWriterService.shouldEnrichIssuePrompt(""));
        assertTrue(IssueWriterService.shouldEnrichIssuePrompt("Brief issue description."));

        // Generate exactly 100 words
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            sb.append("word").append(i).append(" ");
        }
        String hundredWords = sb.toString().trim();
        assertFalse(
                IssueWriterService.shouldEnrichIssuePrompt(hundredWords),
                "Should NOT enrich when body is exactly 100 words (threshold is < 100)");

        // Generate 99 words
        sb = new StringBuilder();
        for (int i = 0; i < 99; i++) {
            sb.append("word").append(i).append(" ");
        }
        String ninetyNineWords = sb.toString().trim();
        assertTrue(
                IssueWriterService.shouldEnrichIssuePrompt(ninetyNineWords),
                "Should enrich when body is 99 words (threshold is < 100)");
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
                false,
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
        assertEquals(JobSpec.DEFAULT_MAX_ISSUE_FIX_ATTEMPTS, spec.effectiveMaxIssueFixAttempts());
    }

    @Test
    void testJobSpecOfIssue_blankBuildSettingsJson_omitsTagEntirely() {
        // Blank buildSettingsJson should result in no build_settings tag
        JobSpec specBlank = JobSpec.ofIssue("gpt-4", null, "token", "owner", "repo", 1, "");
        assertNull(specBlank.getBuildSettingsJson(), "Blank buildSettingsJson should omit tag entirely");

        JobSpec specWhitespace = JobSpec.ofIssue("gpt-4", null, "token", "owner", "repo", 2, "   ");
        assertNull(specWhitespace.getBuildSettingsJson(), "Whitespace-only buildSettingsJson should omit tag");

        JobSpec specNull = JobSpec.ofIssue("gpt-4", null, "token", "owner", "repo", 3, null);
        assertNull(specNull.getBuildSettingsJson(), "Null buildSettingsJson should omit tag");
    }

    @Test
    void testResolveIssueBuildDetails_blankSpecSettings_fallsBackToProjectBuildDetails() {
        // Create a TestProject with non-empty build details
        var project = new TestProject(tempDir);
        var projectBuildDetails = new BuildDetails("./gradlew lint", "./gradlew test", "", Set.of());
        project.setBuildDetails(projectBuildDetails);

        // Create JobSpec with blank build settings (tag omitted)
        JobSpec spec = JobSpec.ofIssue("gpt-4", null, "token", "owner", "repo", 42, "");
        assertNull(spec.getBuildSettingsJson(), "Precondition: blank buildSettingsJson should omit tag");

        // Resolve should fall back to project build details
        BuildDetails resolved = JobRunner.resolveIssueBuildDetails(spec, project);

        assertEquals(projectBuildDetails, resolved, "Should fall back to project build details when spec is blank");
        assertEquals("./gradlew lint", resolved.buildLintCommand());
        assertEquals("./gradlew test", resolved.testAllCommand());
    }

    @Test
    void testResolveIssueBuildDetails_nonBlankSpecSettings_usesSpecOverride() {
        // Create a TestProject with some build details
        var project = new TestProject(tempDir);
        var projectBuildDetails = new BuildDetails("./gradlew projectLint", "./gradlew projectTest", "", Set.of());
        project.setBuildDetails(projectBuildDetails);

        // Create JobSpec with explicit build settings
        String specJson = "{\"buildLintCommand\":\"./mvn lint\",\"testAllCommand\":\"./mvn test\"}";
        JobSpec spec = JobSpec.ofIssue("gpt-4", null, "token", "owner", "repo", 42, specJson);
        assertEquals(specJson, spec.getBuildSettingsJson(), "Precondition: non-blank settings should be stored");

        // Resolve should use spec's build settings, not project's
        BuildDetails resolved = JobRunner.resolveIssueBuildDetails(spec, project);

        assertEquals("./mvn lint", resolved.buildLintCommand());
        assertEquals("./mvn test", resolved.testAllCommand());
        assertNotEquals(projectBuildDetails, resolved, "Should use spec override, not project fallback");
    }

    @Test
    void testResolveIssueBuildDetails_blankSpecSettings_projectAlsoEmpty_returnsEmpty() {
        // Create a TestProject with EMPTY build details (simulating no repo-level config)
        var project = new TestProject(tempDir);
        // Default TestProject has EMPTY build details

        // Create JobSpec with blank build settings
        JobSpec spec = JobSpec.ofIssue("gpt-4", null, "token", "owner", "repo", 42, "");

        // Resolve should return EMPTY (both spec and project are empty)
        BuildDetails resolved = JobRunner.resolveIssueBuildDetails(spec, project);

        assertEquals(BuildDetails.EMPTY, resolved, "Should return EMPTY when both spec and project have no config");
    }

    @Test
    void testIssueBranchNameGeneration() throws Exception {
        // Verify branch name generation for issues uses randomized suffix.
        // The generator produces brokk/issue-<n>-<6char>, but sanitizeBranchName may append "-<k>" on collision.
        String branchName = IssueService.generateBranchNameWithRandomSuffix(42, repo);
        assertTrue(
                branchName.matches("brokk/issue-42-[a-z0-9]{6}(-\\\\d+)?"),
                () -> "branchName should be brokk/issue-42-<6chars> optionally suffixed with -N but was: "
                        + branchName);

        // For collision handling use the deterministic seam so tests are stable.
        repo.getGit().branchCreate().setName("brokk/issue-42-deterministic").call();
        repo.invalidateCaches();

        String secondBranchName = IssueService.generateBranchName(42, repo, "deterministic");
        assertEquals("brokk/issue-42-deterministic-2", secondBranchName);
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

        BuildDetails details = IssueService.parseBuildSettings(json);
        assertEquals("./gradlew classes", details.buildLintCommand());
        assertEquals("./gradlew test", details.testAllCommand());
        assertEquals("./gradlew test --tests", details.testSomeCommand());
    }

    @Test
    void testParseBuildSettingsEmpty() {
        // Null or blank input should return EMPTY
        assertEquals(BuildDetails.EMPTY, IssueService.parseBuildSettings(null));
        assertEquals(BuildDetails.EMPTY, IssueService.parseBuildSettings(""));
        assertEquals(BuildDetails.EMPTY, IssueService.parseBuildSettings("   "));
    }

    @Test
    void testBranchCreationAndCheckout() throws Exception {
        // Use randomized generator and validate format rather than exact deterministic name
        String branchName = IssueService.generateBranchNameWithRandomSuffix(99, repo);
        String prefix = "brokk/issue-99-";
        assertTrue(branchName.startsWith(prefix), () -> "branchName should start with " + prefix);
        String suffix = branchName.substring(prefix.length());
        assertEquals(6, suffix.length(), "random suffix should be 6 characters");
        assertTrue(suffix.matches("[a-z0-9]{6}"), "random suffix should be lowercase alphanumeric");

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
    void testSingleFixVerificationBehavior() throws Exception {
        var verificationCalls = new AtomicInteger(0);
        var fixCalls = new AtomicInteger(0);
        var io = new TestConsoleIO();

        Supplier<String> verificationRunner = () -> {
            int c = verificationCalls.incrementAndGet();
            // First verification fails, second verification also fails to exercise exception path.
            return c == 1 ? "initial failure" : "still failing";
        };
        Consumer<String> fixRunner = prompt -> fixCalls.incrementAndGet();

        IssueExecutionException ex = assertThrows(
                IssueExecutionException.class,
                () -> JobRunner.runSingleFixVerificationGate(
                        "job-single-fix-1", store, io, "./gradlew test", verificationRunner, fixRunner));

        assertEquals(2, verificationCalls.get(), "Verification should be called exactly twice");
        assertEquals(1, fixCalls.get(), "Fix runner should be called exactly once");
        assertTrue(ex.getMessage().contains("Verification failed after single fix attempt"));

        var events = store.readEvents("job-single-fix-1", -1, 0);
        var commandEvents = events.stream()
                .filter(e -> e.type().equals(JobRunner.COMMAND_RESULT_EVENT_TYPE))
                .toList();

        var verificationEvents = commandEvents.stream()
                .filter(e -> e.data() instanceof Map && "verification".equals(((Map<?, ?>) e.data()).get("stage")))
                .toList();

        assertEquals(2, verificationEvents.size(), "Should emit one command result per verification run");

        assertTrue(verificationEvents.getFirst().data() instanceof Map);
        @SuppressWarnings("unchecked")
        var first = (Map<String, Object>) verificationEvents.getFirst().data();
        assertEquals("verification", first.get("stage"));
        assertEquals("./gradlew test", first.get("command"));
        assertEquals(1, ((Number) first.get("attempt")).intValue());
        assertEquals(Boolean.FALSE, first.get("success"));
        assertEquals("initial failure", first.get("output"));

        @SuppressWarnings("unchecked")
        var second = (Map<String, Object>) verificationEvents.getLast().data();
        assertEquals("verification", second.get("stage"));
        assertEquals("./gradlew test", second.get("command"));
        assertEquals(2, ((Number) second.get("attempt")).intValue());
        assertEquals(Boolean.FALSE, second.get("success"));
        assertEquals("still failing", second.get("output"));
    }

    @Test
    void issueModeTestLintRetryLoop_testFailure_triggersFixTaskWithExactPrefix_andSkipsLintThatIteration() {
        var cancelled = new AtomicBoolean(false);

        var calls = new ArrayList<String>();
        var prompts = new ArrayList<String>();

        BiConsumer<Integer, String> progressSink = (attempt, msg) -> {};

        Function<String, String> commandRunner = cmd -> {
            calls.add(cmd);
            return "TEST FAILED OUTPUT";
        };

        Consumer<String> fixTaskRunner = out -> prompts.add("fix this build error:\n" + out);

        assertThrows(
                IssueExecutionException.class,
                () -> JobRunner.runIssueModeTestLintRetryLoop(
                        cancelled::get,
                        progressSink,
                        commandRunner,
                        fixTaskRunner,
                        new BuildDetails("./gradlew lint", "./gradlew test", "", Set.of()),
                        2));

        assertEquals(List.of("./gradlew test", "./gradlew test"), calls, "Lint must be skipped when tests fail");
        assertEquals(2, prompts.size());
        assertTrue(prompts.get(0).startsWith("fix this build error:"), "Prefix must be exact and first");
        assertEquals("fix this build error:\nTEST FAILED OUTPUT", prompts.get(0));
    }

    @Test
    void issueModeTestLintRetryLoop_runsTestsThenLint_inThatOrder_andSkipsLintWhenTestsFail_iterationScoped() {
        var cancelled = new AtomicBoolean(false);

        String testCmd = "./gradlew testAll";
        String lintCmd = "./gradlew lintAll";

        var calls = new ArrayList<String>();

        BiConsumer<Integer, String> progressSink = (attempt, msg) -> {};

        Function<String, String> commandRunner = cmd -> {
            calls.add(cmd);

            if (cmd.equals(testCmd)) {
                return "TESTS FAILED";
            }
            if (cmd.equals(lintCmd)) {
                return fail("Lint must be skipped when tests fail in that iteration");
            }

            return fail("Unexpected command: " + cmd);
        };

        Consumer<String> fixTaskRunner = out -> {};

        assertThrows(
                IssueExecutionException.class,
                () -> JobRunner.runIssueModeTestLintRetryLoop(
                        cancelled::get,
                        progressSink,
                        commandRunner,
                        fixTaskRunner,
                        new BuildDetails(lintCmd, testCmd, "", Set.of()),
                        2));

        assertEquals(List.of(testCmd, testCmd), calls, "Should run only tests each iteration; lint is skipped");
    }

    @Test
    void issueModeTestLintRetryLoop_runsTestsThenLint_inThatOrder_whenTestsPass() {
        var cancelled = new AtomicBoolean(false);

        String testCmd = "./gradlew testAll";
        String lintCmd = "./gradlew lintAll";

        int maxIterations = 3;

        var calls = new ArrayList<String>();

        BiConsumer<Integer, String> progressSink = (attempt, msg) -> {};

        Function<String, String> commandRunner = cmd -> {
            calls.add(cmd);

            if (cmd.equals(testCmd)) {
                return "";
            }
            if (cmd.equals(lintCmd)) {
                return "";
            }

            return fail("Unexpected command: " + cmd);
        };

        Consumer<String> fixTaskRunner = out -> fail("No fix tasks when both pass");

        JobRunner.runIssueModeTestLintRetryLoop(
                cancelled::get,
                progressSink,
                commandRunner,
                fixTaskRunner,
                new BuildDetails(lintCmd, testCmd, "", Set.of()),
                maxIterations);

        assertEquals(List.of(testCmd, lintCmd), calls, "Must run tests first, then lint second");
    }

    @Test
    void issueModeTestLintRetryLoop_lintFailure_triggersFixTaskWithExactPrefix() {
        var cancelled = new AtomicBoolean(false);

        var calls = new ArrayList<String>();
        var fixPrompts = new ArrayList<String>();

        BiConsumer<Integer, String> progressSink = (attempt, msg) -> {};

        Function<String, String> commandRunner = cmd -> {
            calls.add(cmd);
            if (calls.size() % 2 == 1) {
                return "";
            }
            return "LINT FAILED OUTPUT";
        };

        Consumer<String> fixTaskRunner = out -> fixPrompts.add("fix this build error:\n" + out);

        int maxIterations = 2;

        IssueExecutionException ex = assertThrows(
                IssueExecutionException.class,
                () -> JobRunner.runIssueModeTestLintRetryLoop(
                        cancelled::get,
                        progressSink,
                        commandRunner,
                        fixTaskRunner,
                        new BuildDetails("./gradlew lint", "./gradlew test", "", Set.of()),
                        maxIterations));

        assertEquals(List.of("./gradlew test", "./gradlew lint", "./gradlew test", "./gradlew lint"), calls);
        assertEquals(2, fixPrompts.size());
        assertEquals("fix this build error:\nLINT FAILED OUTPUT", fixPrompts.get(0));

        String msg = ex.getMessage();
        assertNotNull(msg);
        assertTrue(msg.contains("Tests/lint failed after " + maxIterations + " iteration(s)"));
        assertTrue(msg.contains("stage=lint"));
        assertTrue(msg.contains("LINT FAILED OUTPUT"));
    }

    @Test
    void issueModeTestLintRetryLoop_exitsEarlyWhenBothPass() {
        var cancelled = new AtomicBoolean(false);

        int maxIterations = 3;

        var calls = new ArrayList<String>();
        var fixCalls = new AtomicInteger(0);

        BiConsumer<Integer, String> progressSink = (attempt, msg) -> {};

        Function<String, String> commandRunner = cmd -> {
            calls.add(cmd);
            return "";
        };

        Consumer<String> fixTaskRunner = out -> fixCalls.incrementAndGet();

        JobRunner.runIssueModeTestLintRetryLoop(
                cancelled::get,
                progressSink,
                commandRunner,
                fixTaskRunner,
                new BuildDetails("./gradlew lint", "./gradlew test", "", Set.of()),
                maxIterations);

        assertEquals(List.of("./gradlew test", "./gradlew lint"), calls, "Should run tests then lint once");
        assertEquals(0, fixCalls.get(), "No fix tasks when both pass");
    }

    @Test
    void issueModeTestLintRetryLoop_emitsCommandResultEvents_thatRoundTripThroughJobStore() throws Exception {
        var cancelled = new AtomicBoolean(false);

        String jobId = "job-final-verification-events-1";

        var io = new TestConsoleIO();

        BiConsumer<Integer, String> progressSink = (attempt, msg) -> {};
        Consumer<String> fixTaskRunner = out -> fail("No fix tasks expected");

        Function<String, String> commandRunner = cmd -> "";

        JobRunner.runIssueModeTestLintRetryLoop(
                jobId,
                store,
                io,
                cancelled::get,
                progressSink,
                commandRunner,
                fixTaskRunner,
                new BuildDetails("./gradlew lint", "./gradlew test", "", Set.of()),
                3);

        var events = store.readEvents(jobId, -1, 0);
        var commandEvents = events.stream()
                .filter(e -> e.type().equals(JobRunner.COMMAND_RESULT_EVENT_TYPE))
                .toList();

        assertEquals(2, commandEvents.size(), "Should emit tests and lint command results");

        assertTrue(commandEvents.getFirst().data() instanceof Map);
        @SuppressWarnings("unchecked")
        var first = (Map<String, Object>) commandEvents.getFirst().data();
        assertEquals("tests", first.get("stage"));
        assertEquals("./gradlew test", first.get("command"));
        assertEquals(Boolean.FALSE, first.get("skipped"));
        assertEquals(Boolean.TRUE, first.get("success"));
        assertEquals("", first.get("output"));
        assertEquals(1, ((Number) first.get("attempt")).intValue());

        @SuppressWarnings("unchecked")
        var second = (Map<String, Object>) commandEvents.getLast().data();
        assertEquals("lint", second.get("stage"));
        assertEquals("./gradlew lint", second.get("command"));
        assertEquals(Boolean.FALSE, second.get("skipped"));
        assertEquals(Boolean.TRUE, second.get("success"));
        assertEquals("", second.get("output"));
        assertEquals(1, ((Number) second.get("attempt")).intValue());
    }

    @Test
    void issueModeTestLintRetryLoop_whenTestsFail_emitsTestsEventAndLintSkippedEvent_forThatIteration()
            throws Exception {
        var cancelled = new AtomicBoolean(false);

        String jobId = "job-final-verification-events-tests-fail-1";
        var io = new TestConsoleIO();

        String testCmd = "./gradlew test";
        String lintCmd = "./gradlew lint";

        BiConsumer<Integer, String> progressSink = (attempt, msg) -> {};

        var calls = new ArrayList<String>();
        Function<String, String> commandRunner = cmd -> {
            calls.add(cmd);
            if (cmd.equals(testCmd)) {
                return "TESTS FAILED OUTPUT";
            }
            return fail("Only tests command should run; lint must be skipped when tests fail");
        };

        var fixCalls = new AtomicInteger(0);
        Consumer<String> fixTaskRunner = out -> fixCalls.incrementAndGet();

        assertThrows(
                IssueExecutionException.class,
                () -> JobRunner.runIssueModeTestLintRetryLoop(
                        jobId,
                        store,
                        io,
                        cancelled::get,
                        progressSink,
                        commandRunner,
                        fixTaskRunner,
                        new BuildDetails(lintCmd, testCmd, "", Set.of()),
                        1));

        assertEquals(List.of(testCmd), calls);

        var events = store.readEvents(jobId, -1, 0);
        var commandEvents = events.stream()
                .filter(e -> e.type().equals(JobRunner.COMMAND_RESULT_EVENT_TYPE))
                .toList();

        assertEquals(2, commandEvents.size(), "Attempt should record both stages: tests result and lint skipped");

        assertTrue(commandEvents.getFirst().data() instanceof Map);
        @SuppressWarnings("unchecked")
        var testsEvent = (Map<String, Object>) commandEvents.getFirst().data();
        assertEquals("tests", testsEvent.get("stage"));
        assertEquals(testCmd, testsEvent.get("command"));
        assertEquals(1, ((Number) testsEvent.get("attempt")).intValue());
        assertEquals(Boolean.FALSE, testsEvent.get("skipped"));
        assertEquals(Boolean.FALSE, testsEvent.get("success"));
        assertEquals("TESTS FAILED OUTPUT", testsEvent.get("output"));

        assertTrue(commandEvents.getLast().data() instanceof Map);
        @SuppressWarnings("unchecked")
        var lintEvent = (Map<String, Object>) commandEvents.getLast().data();
        assertEquals("lint", lintEvent.get("stage"));
        assertEquals(lintCmd, lintEvent.get("command"));
        assertEquals(1, ((Number) lintEvent.get("attempt")).intValue());
        assertEquals(Boolean.TRUE, lintEvent.get("skipped"));
        assertEquals("tests_failed", lintEvent.get("skipReason"));
        assertEquals(Boolean.TRUE, lintEvent.get("success"));
        assertEquals("", lintEvent.get("output"));

        assertEquals(1, fixCalls.get(), "One fix attempt should be triggered for failed tests output");
    }

    @Test
    void issueModeTestLintRetryLoop_whenLintFailsAfterTestsPass_emitsBothEventsWithCorrectOutputs() throws Exception {
        var cancelled = new AtomicBoolean(false);

        String jobId = "job-final-verification-events-lint-fail-1";
        var io = new TestConsoleIO();

        String testCmd = "./gradlew test";
        String lintCmd = "./gradlew lint";

        BiConsumer<Integer, String> progressSink = (attempt, msg) -> {};

        Function<String, String> commandRunner = cmd -> {
            if (cmd.equals(testCmd)) {
                return "";
            }
            if (cmd.equals(lintCmd)) {
                return "LINT FAILED OUTPUT";
            }
            return fail("Unexpected command: " + cmd);
        };

        assertThrows(
                IssueExecutionException.class,
                () -> JobRunner.runIssueModeTestLintRetryLoop(
                        jobId,
                        store,
                        io,
                        cancelled::get,
                        progressSink,
                        commandRunner,
                        out -> {},
                        new BuildDetails(lintCmd, testCmd, "", Set.of()),
                        1));

        var events = store.readEvents(jobId, -1, 0);
        var commandEvents = events.stream()
                .filter(e -> e.type().equals(JobRunner.COMMAND_RESULT_EVENT_TYPE))
                .toList();

        assertEquals(2, commandEvents.size(), "Attempt should record both tests and lint results");

        assertTrue(commandEvents.getFirst().data() instanceof Map);
        @SuppressWarnings("unchecked")
        var testsEvent = (Map<String, Object>) commandEvents.getFirst().data();
        assertEquals("tests", testsEvent.get("stage"));
        assertEquals(testCmd, testsEvent.get("command"));
        assertEquals(1, ((Number) testsEvent.get("attempt")).intValue());
        assertEquals(Boolean.FALSE, testsEvent.get("skipped"));
        assertEquals(Boolean.TRUE, testsEvent.get("success"));
        assertEquals("", testsEvent.get("output"));

        assertTrue(commandEvents.getLast().data() instanceof Map);
        @SuppressWarnings("unchecked")
        var lintEvent = (Map<String, Object>) commandEvents.getLast().data();
        assertEquals("lint", lintEvent.get("stage"));
        assertEquals(lintCmd, lintEvent.get("command"));
        assertEquals(1, ((Number) lintEvent.get("attempt")).intValue());
        assertEquals(Boolean.FALSE, lintEvent.get("skipped"));
        assertEquals(Boolean.FALSE, lintEvent.get("success"));
        assertEquals("LINT FAILED OUTPUT", lintEvent.get("output"));
    }

    @Test
    void issueModeTestLintRetryLoop_throwsIllegalArgumentException_whenMaxIterationsIsZero_andDoesNotInvokeCallbacks() {
        var cancelled = new AtomicBoolean(false);

        BiConsumer<Integer, String> progressSink = (attempt, msg) -> fail("progressSink must not be invoked");
        Function<String, String> commandRunner = cmd -> fail("commandRunner must not be invoked");
        Consumer<String> fixTaskRunner = out -> fail("fixTaskRunner must not be invoked");

        var ex = assertThrows(
                IllegalArgumentException.class,
                () -> JobRunner.runIssueModeTestLintRetryLoop(
                        cancelled::get,
                        progressSink,
                        commandRunner,
                        fixTaskRunner,
                        new BuildDetails("./gradlew lint", "./gradlew test", "", Set.of()),
                        0));

        assertEquals("maxIterations must be >= 1", ex.getMessage());
    }

    @Test
    void
            issueModeTestLintRetryLoop_throwsIllegalArgumentException_whenMaxIterationsIsNegative_andDoesNotInvokeCallbacks() {
        var cancelled = new AtomicBoolean(false);

        BiConsumer<Integer, String> progressSink = (attempt, msg) -> fail("progressSink must not be invoked");
        Function<String, String> commandRunner = cmd -> fail("commandRunner must not be invoked");
        Consumer<String> fixTaskRunner = out -> fail("fixTaskRunner must not be invoked");

        var ex = assertThrows(
                IllegalArgumentException.class,
                () -> JobRunner.runIssueModeTestLintRetryLoop(
                        cancelled::get,
                        progressSink,
                        commandRunner,
                        fixTaskRunner,
                        new BuildDetails("./gradlew lint", "./gradlew test", "", Set.of()),
                        -1));

        assertEquals("maxIterations must be >= 1", ex.getMessage());
    }

    @Test
    void issueModeTestLintRetryLoop_throwsIssueCancelledException_whenCancelled() {
        var cancelled = new AtomicBoolean(true);

        int maxIterations = 3;

        BiConsumer<Integer, String> progressSink = (attempt, msg) -> {};

        Function<String, String> commandRunner = cmd -> fail("Command must not run when cancelled");
        Consumer<String> fixTaskRunner = out -> fail("Fix task must not run when cancelled");

        assertThrows(
                JobRunner.IssueCancelledException.class,
                () -> JobRunner.runIssueModeTestLintRetryLoop(
                        cancelled::get,
                        progressSink,
                        commandRunner,
                        fixTaskRunner,
                        new BuildDetails("./gradlew lint", "./gradlew test", "", Set.of()),
                        maxIterations));
    }

    @Test
    void issueModeTestLintRetryLoop_throwsAfterMaxIterationsIfNeverSucceeds() {
        var cancelled = new AtomicBoolean(false);

        int maxIterations = 3;

        var fixCalls = new AtomicInteger(0);
        var testCalls = new AtomicInteger(0);

        BiConsumer<Integer, String> progressSink = (attempt, msg) -> {};

        Function<String, String> commandRunner = cmd -> {
            testCalls.incrementAndGet();
            return "always failing";
        };

        Consumer<String> fixTaskRunner = out -> fixCalls.incrementAndGet();

        IssueExecutionException ex = assertThrows(
                IssueExecutionException.class,
                () -> JobRunner.runIssueModeTestLintRetryLoop(
                        cancelled::get,
                        progressSink,
                        commandRunner,
                        fixTaskRunner,
                        new BuildDetails("./gradlew lint", "./gradlew test", "", Set.of()),
                        maxIterations));

        assertEquals(maxIterations, testCalls.get(), "Must run exactly maxIterations iterations");
        assertEquals(maxIterations, fixCalls.get(), "Must perform exactly one fix per iteration");

        String msg = ex.getMessage();
        assertNotNull(msg);
        assertTrue(msg.contains("Tests/lint failed after " + maxIterations + " iteration(s)"));
        assertTrue(msg.contains("stage=tests"));
        assertTrue(msg.contains("always failing"));
    }

    @Test
    void
            issueModeTestLintRetryLoop_blankTestCommand_doesNotInvokeTests_invokesLint_andFixesOncePerIteration_untilMaxIterations() {
        var cancelled = new AtomicBoolean(false);

        String testCmd = "";
        String lintCmd = "./gradlew lint";

        int maxIterations = 3;

        var lintCalls = new AtomicInteger(0);
        var fixCalls = new AtomicInteger(0);

        BiConsumer<Integer, String> progressSink = (attempt, msg) -> {};

        Function<String, String> commandRunner = cmd -> {
            if (cmd.equals(testCmd)) {
                return fail("Test command must not be invoked when testAllCommand() is blank");
            }
            if (cmd.equals(lintCmd)) {
                lintCalls.incrementAndGet();
                return "LINT FAILED OUTPUT";
            }
            return fail("Unexpected command: " + cmd);
        };

        Consumer<String> fixTaskRunner = out -> fixCalls.incrementAndGet();

        IssueExecutionException ex = assertThrows(
                IssueExecutionException.class,
                () -> JobRunner.runIssueModeTestLintRetryLoop(
                        cancelled::get,
                        progressSink,
                        commandRunner,
                        fixTaskRunner,
                        new BuildDetails(lintCmd, testCmd, "", Set.of()),
                        maxIterations));

        assertEquals(maxIterations, lintCalls.get(), "Lint must be invoked once per iteration");
        assertEquals(maxIterations, fixCalls.get(), "Fix must run once per iteration");

        String msg = ex.getMessage();
        assertNotNull(msg);
        assertTrue(msg.contains("Tests/lint failed after " + maxIterations + " iteration(s)"));
        assertTrue(msg.contains("stage=lint"));
        assertTrue(msg.contains("LINT FAILED OUTPUT"));
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

        Function<PrReviewService.InlineComment, String> commentToPrompt = c -> {
            String prompt = JobRunner.buildInlineCommentFixPrompt(c);
            prompts.add(prompt);
            return prompt;
        };

        Consumer<String> taskRunner = prompt -> {
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
        Runnable exec = () -> {
            currentPhase.set("FINAL_VERIFICATION");
            finalVerification.run();
        };
        JobRunner.runIssueReviewTaskSequence(comments, commentToPrompt, taskRunner, branchUpdateHook, exec);

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

        Function<PrReviewService.InlineComment, String> commentToPrompt = c -> {
            promptsBuilt.incrementAndGet();
            return JobRunner.buildInlineCommentFixPrompt(c);
        };

        Consumer<String> taskRunner = prompt -> {
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
    void issueReviewFix_successThenFailure_emitsStructuredCommandResultEvents_roundTripThroughJobStore()
            throws Exception {
        String jobId = "job-review-fix-events-success-then-failure-1";
        var io = new TestConsoleIO();

        var comments = List.of(
                new PrReviewService.InlineComment(
                        "src/main/java/com/acme/A.java", 11, "Null deref risk in foo()", PrReviewService.Severity.HIGH),
                new PrReviewService.InlineComment(
                        "src/main/java/com/acme/B.java",
                        22,
                        "Missing bounds check in bar()",
                        PrReviewService.Severity.CRITICAL));

        var cancelled = new AtomicBoolean(false);
        var ran = new AtomicInteger(0);
        var branchHooks = new AtomicInteger(0);

        Consumer<PrReviewService.InlineComment> runner = c -> {
            int idx = ran.incrementAndGet();
            if (idx == 2) {
                throw new RuntimeException("boom");
            }
        };

        Runnable branchHook = () -> branchHooks.incrementAndGet();

        var thrown = assertThrows(
                RuntimeException.class,
                () -> JobRunner.runIssueReviewFixAttemptsWithCommandResultEvents(
                        jobId, store, io, cancelled::get, comments, runner, branchHook));
        assertEquals("boom", thrown.getMessage());

        assertEquals(2, ran.get(), "Should run until the failure (attempt 2)");
        assertEquals(1, branchHooks.get(), "Branch hook runs only after successful attempt 1");

        var events = store.readEvents(jobId, -1, 0);
        var reviewFixEvents = events.stream()
                .filter(e -> e.type().equals(JobRunner.COMMAND_RESULT_EVENT_TYPE))
                .filter(e -> e.data() instanceof Map && "review_fix".equals(((Map<?, ?>) e.data()).get("stage")))
                .toList();

        assertEquals(2, reviewFixEvents.size(), "Should emit exactly two review_fix COMMAND_RESULT events");

        assertTrue(reviewFixEvents.get(0).data() instanceof Map);
        @SuppressWarnings("unchecked")
        var first = (Map<String, Object>) reviewFixEvents.get(0).data();

        assertEquals("review_fix", first.get("stage"));
        assertEquals("src/main/java/com/acme/A.java:11", first.get("command"));
        assertEquals(1, ((Number) first.get("attempt")).intValue());
        assertEquals(Boolean.FALSE, first.get("skipped"));
        assertEquals(Boolean.TRUE, first.get("success"));

        String firstOut = (String) first.get("output");
        assertNotNull(firstOut);
        assertTrue(firstOut.contains("Finding:"), "Output should include a Finding block");
        assertTrue(firstOut.contains("- path: src/main/java/com/acme/A.java"));
        assertTrue(firstOut.contains("- line: 11"));
        assertTrue(firstOut.contains("- severity: HIGH"));
        assertTrue(firstOut.contains("Null deref risk in foo()"));
        assertTrue(firstOut.contains("Outcome: completed"));

        assertTrue(reviewFixEvents.get(1).data() instanceof Map);
        @SuppressWarnings("unchecked")
        var second = (Map<String, Object>) reviewFixEvents.get(1).data();

        assertEquals("review_fix", second.get("stage"));
        assertEquals("src/main/java/com/acme/B.java:22", second.get("command"));
        assertEquals(2, ((Number) second.get("attempt")).intValue());
        assertEquals(Boolean.FALSE, second.get("skipped"));
        assertEquals(Boolean.FALSE, second.get("success"));

        String secondOut = (String) second.get("output");
        assertNotNull(secondOut);
        assertTrue(secondOut.contains("- path: src/main/java/com/acme/B.java"));
        assertTrue(secondOut.contains("- line: 22"));
        assertTrue(secondOut.contains("- severity: CRITICAL"));
        assertTrue(secondOut.contains("Missing bounds check in bar()"));
        assertTrue(secondOut.contains("Outcome: failed"));

        assertNotNull(second.get("exception"));
        assertTrue(((String) second.get("exception")).contains("RuntimeException: boom"));
    }

    @Test
    void issueReviewFix_cancellationAfterAttempt1_emitsAttempt2SkippedEvent_withCancellationReason() throws Exception {
        String jobId = "job-review-fix-events-cancel-after-1-1";
        var io = new TestConsoleIO();

        var comments = List.of(
                new PrReviewService.InlineComment(
                        "src/main/java/com/acme/A.java",
                        101,
                        "Potential NPE in baz()",
                        PrReviewService.Severity.MEDIUM),
                new PrReviewService.InlineComment(
                        "src/main/java/com/acme/C.java",
                        202,
                        "Unbounded recursion in qux()",
                        PrReviewService.Severity.CRITICAL));

        var cancelled = new AtomicBoolean(false);
        var ran = new AtomicInteger(0);
        var branchHooks = new AtomicInteger(0);

        Consumer<PrReviewService.InlineComment> runner = c -> {
            int idx = ran.incrementAndGet();
            if (idx == 1) {
                cancelled.set(true);
            }
        };
        Runnable branchHook = () -> branchHooks.incrementAndGet();

        JobRunner.runIssueReviewFixAttemptsWithCommandResultEvents(
                jobId, store, io, cancelled::get, comments, runner, branchHook);

        assertEquals(1, ran.get(), "Attempt 2 should not execute after cancellation flips true");
        assertEquals(1, branchHooks.get(), "Branch hook runs only for executed attempt 1");

        var events = store.readEvents(jobId, -1, 0);
        var reviewFixEvents = events.stream()
                .filter(e -> e.type().equals(JobRunner.COMMAND_RESULT_EVENT_TYPE))
                .filter(e -> e.data() instanceof Map && "review_fix".equals(((Map<?, ?>) e.data()).get("stage")))
                .toList();

        assertEquals(2, reviewFixEvents.size(), "Should emit attempt 1 result and attempt 2 skipped");

        assertTrue(reviewFixEvents.get(0).data() instanceof Map);
        @SuppressWarnings("unchecked")
        var first = (Map<String, Object>) reviewFixEvents.get(0).data();
        assertEquals(1, ((Number) first.get("attempt")).intValue());
        assertEquals(Boolean.FALSE, first.get("skipped"));
        assertEquals(Boolean.TRUE, first.get("success"));

        assertTrue(reviewFixEvents.get(1).data() instanceof Map);
        @SuppressWarnings("unchecked")
        var second = (Map<String, Object>) reviewFixEvents.get(1).data();

        assertEquals("review_fix", second.get("stage"));
        assertEquals("src/main/java/com/acme/C.java:202", second.get("command"));
        assertEquals(2, ((Number) second.get("attempt")).intValue());
        assertEquals(Boolean.TRUE, second.get("skipped"));

        assertNotNull(second.get("skipReason"));
        assertTrue(
                ((String) second.get("skipReason")).toLowerCase().contains("cancel"),
                "skipReason should indicate cancellation");
        assertEquals(Boolean.TRUE, second.get("success"));

        String out = (String) second.get("output");
        assertNotNull(out);
        assertTrue(out.contains("Outcome: skipped"));
        assertTrue(out.contains("- path: src/main/java/com/acme/C.java"));
        assertTrue(out.contains("- line: 202"));
    }

    @Test
    void issueReviewFix_whenCancelledBeforeStart_emitsSkippedEventsForAllAttempts() throws Exception {
        String jobId = "job-review-fix-events-cancelled-1";
        var io = new TestConsoleIO();

        var comments = List.of(
                new PrReviewService.InlineComment("src/A.java", 10, "First issue", PrReviewService.Severity.HIGH),
                new PrReviewService.InlineComment("src/B.java", 20, "Second issue", PrReviewService.Severity.CRITICAL));

        var cancelled = new AtomicBoolean(true);
        var ran = new AtomicInteger(0);
        var branchHooks = new AtomicInteger(0);

        Consumer<PrReviewService.InlineComment> runner = c -> ran.incrementAndGet();
        Runnable branchHook = () -> branchHooks.incrementAndGet();

        JobRunner.runIssueReviewFixAttemptsWithCommandResultEvents(
                jobId, store, io, cancelled::get, comments, runner, branchHook);

        assertEquals(0, ran.get(), "No tasks should run when already cancelled");
        assertEquals(0, branchHooks.get(), "No branch hooks should run when cancelled");

        var events = store.readEvents(jobId, -1, 0);
        var commandEvents = events.stream()
                .filter(e -> e.type().equals(JobRunner.COMMAND_RESULT_EVENT_TYPE))
                .toList();

        assertEquals(2, commandEvents.size(), "Must emit exactly one skipped event per comment");

        @SuppressWarnings("unchecked")
        var first = (Map<String, Object>) commandEvents.get(0).data();
        assertEquals("review_fix", first.get("stage"));
        assertEquals("src/A.java:10", first.get("command"));
        assertEquals(1, ((Number) first.get("attempt")).intValue());
        assertEquals(Boolean.TRUE, first.get("skipped"));
        assertEquals("cancelled", first.get("skipReason"));
        assertEquals(Boolean.TRUE, first.get("success"));
        assertTrue(((String) first.get("output")).contains("Outcome: skipped"));

        @SuppressWarnings("unchecked")
        var second = (Map<String, Object>) commandEvents.get(1).data();
        assertEquals("review_fix", second.get("stage"));
        assertEquals("src/B.java:20", second.get("command"));
        assertEquals(2, ((Number) second.get("attempt")).intValue());
        assertEquals(Boolean.TRUE, second.get("skipped"));
        assertEquals("cancelled", second.get("skipReason"));
        assertEquals(Boolean.TRUE, second.get("success"));
        assertTrue(((String) second.get("output")).contains("Outcome: skipped"));
    }

    @Test
    void testPrSkippedWhenFinalVerificationStillFails() {
        var verificationCalls = new AtomicInteger(0);
        var fixCalls = new AtomicInteger(0);
        var io = new TestConsoleIO();
        var prCreated = new AtomicBoolean(false);

        Supplier<String> verificationRunner = () -> {
            verificationCalls.incrementAndGet();
            return "still failing";
        };
        Consumer<String> fixRunner = prompt -> fixCalls.incrementAndGet();

        assertThrows(IssueExecutionException.class, () -> {
            JobRunner.runSingleFixVerificationGate(
                    "job-pr-skip-1", store, io, "verification", verificationRunner, fixRunner);
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

    // runIssueModeBuildLintRetryLoop was intentionally removed; tests should target runIssueModeTestLintRetryLoop
    // instead.
}
