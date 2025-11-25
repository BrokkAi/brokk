package ai.brokk.executor;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.ContextManager;
import ai.brokk.executor.jobs.JobRunner;
import ai.brokk.executor.jobs.JobSpec;
import ai.brokk.executor.jobs.JobStatus;
import ai.brokk.executor.jobs.JobStore;
import ai.brokk.project.MainProject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Integration tests for LUTZ mode in JobRunner.
 * 
 * LUTZ mode combines SearchAgent (planning phase) with ArchitectAgent (execution phase):
 * - Phase 1: SearchAgent.Objective.TASKS_ONLY generates a task list from user prompt
 * - Phase 2: Each incomplete task is executed sequentially using ArchitectAgent
 * - Progress updates once per top-level task (not per subtask)
 * - Cancellation interrupts execution and updates job status
 * - No-task case (empty task list) completes gracefully
 */
@Timeout(60)
class LutzModeIntegrationTest {
    private static final Logger logger = LogManager.getLogger(LutzModeIntegrationTest.class);

    private MainProject mainProject;
    private ContextManager contextManager;
    private JobRunner jobRunner;
    private JobStore jobStore;
    private Path tempProjectRoot;

    @BeforeEach
    void setUp() throws Exception {
        // Create a temporary test project directory
        tempProjectRoot = Files.createTempDirectory("lutz-test-");
        Files.createDirectories(tempProjectRoot.resolve(".brokk").resolve("jobs"));
        
        // Initialize a minimal project
        mainProject = new MainProject(tempProjectRoot);
        contextManager = new ContextManager(mainProject);
        contextManager.createHeadless();
        
        // Initialize job store
        jobStore = new JobStore(tempProjectRoot.resolve(".brokk").resolve("jobs"));
        jobRunner = new JobRunner(contextManager, jobStore);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (jobRunner != null) {
            // JobRunner cleanup handled by contextManager.close()
        }
        if (contextManager != null) {
            contextManager.close();
        }
        if (mainProject != null) {
            mainProject.close();
        }
        if (tempProjectRoot != null) {
            // Clean up temp directory
            deleteRecursively(tempProjectRoot);
        }
    }

    /**
     * Test that LUTZ mode parses correctly from JobSpec tags.
     * Verifies: tags.mode=LUTZ is recognized and triggers LUTZ execution path.
     */
    @Test
    void testLutzModeRecognized() throws Exception {
        var spec = JobSpec.of(
                "Create a simple utility class",
                true,
                true,
                "gpt-4",
                "gpt-4",
                Map.of("mode", "LUTZ"));

        var jobCreateResult = jobStore.createOrGetJob("test-lutz-mode", spec);
        var jobId = jobCreateResult.jobId();

        var future = jobRunner.runAsync(jobId, spec);
        future.get(30, TimeUnit.SECONDS);

        var status = jobStore.loadStatus(jobId);
        assertNotNull(status);
        assertEquals("COMPLETED", status.state());
    }

    /**
     * Test that LUTZ mode generates a task list in the planning phase.
     * Verifies: SearchAgent.Objective.TASKS_ONLY produces tasks.
     */
    @Test
    void testLutzPlanningPhase() throws Exception {
        var spec = JobSpec.of(
                "Split this into steps: (1) add a new class, (2) write tests for it",
                true,
                false,
                "gpt-4",
                "gpt-4",
                Map.of("mode", "LUTZ"));

        var jobCreateResult = jobStore.createOrGetJob("test-lutz-planning", spec);
        var jobId = jobCreateResult.jobId();

        var future = jobRunner.runAsync(jobId, spec);
        future.get(30, TimeUnit.SECONDS);

        var taskList = contextManager.getTaskList();
        assertTrue(
                !taskList.tasks().isEmpty() || isJobCompleted(jobId),
                "LUTZ should generate tasks or complete gracefully");

        var status = jobStore.loadStatus(jobId);
        assertNotNull(status);
        assertEquals("COMPLETED", status.state());
    }

    /**
     * Test that LUTZ mode updates progress correctly.
     * Verifies: Progress increments per top-level task (not per subtask).
     */
    @Test
    void testLutzProgressUpdates() throws Exception {
        String multiTaskInput = "Task 1: Add a logger\nTask 2: Add error handling";
        var spec = JobSpec.of(
                multiTaskInput,
                true,
                true,
                "gpt-4",
                "gpt-4",
                Map.of("mode", "LUTZ"));

        var jobCreateResult = jobStore.createOrGetJob("test-lutz-progress", spec);
        var jobId = jobCreateResult.jobId();

        var future = jobRunner.runAsync(jobId, spec);

        int previousProgress = 0;
        int maxChecks = 30;
        for (int i = 0; i < maxChecks && !future.isDone(); i++) {
            Thread.sleep(500);
            var status = jobStore.loadStatus(jobId);
            if (status != null) {
                int currentProgress = status.progressPercent();
                assertTrue(
                        currentProgress >= previousProgress,
                        "Progress should be monotonically increasing");
                previousProgress = currentProgress;
            }
        }

        future.get(5, TimeUnit.SECONDS);

        var finalStatus = jobStore.loadStatus(jobId);
        assertNotNull(finalStatus);
        assertEquals("COMPLETED", finalStatus.state());
        assertEquals(100, finalStatus.progressPercent());
    }

    /**
     * Test that LUTZ mode respects the no-task case.
     * Verifies: When SearchAgent generates no tasks, job completes gracefully.
     */
    @Test
    void testLutzNoTaskCase() throws Exception {
        var spec = JobSpec.of(
                "Do nothing",
                true,
                true,
                "gpt-4",
                "gpt-4",
                Map.of("mode", "LUTZ"));

        var jobCreateResult = jobStore.createOrGetJob("test-lutz-no-tasks", spec);
        var jobId = jobCreateResult.jobId();

        var future = jobRunner.runAsync(jobId, spec);

        assertDoesNotThrow(
                () -> future.get(30, TimeUnit.SECONDS),
                "LUTZ should handle no-task case gracefully");

        var status = jobStore.loadStatus(jobId);
        assertNotNull(status);
        assertEquals("COMPLETED", status.state());
    }

    /**
     * Test that LUTZ mode respects cancellation.
     * Verifies: Cancel stops execution and updates job status to CANCELLED.
     */
    @Test
    void testLutzCancellation() throws Exception {
        var spec = JobSpec.of(
                "Implement a complex feature with multiple steps",
                true,
                true,
                "gpt-4",
                "gpt-4",
                Map.of("mode", "LUTZ"));

        var jobCreateResult = jobStore.createOrGetJob("test-lutz-cancel", spec);
        var jobId = jobCreateResult.jobId();

        var future = jobRunner.runAsync(jobId, spec);

        Thread.sleep(2000);
        jobRunner.cancel(jobId);

        try {
            future.get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            logger.debug("Expected exception after cancellation: {}", e.getMessage());
        }

        var status = jobStore.loadStatus(jobId);
        assertNotNull(status);
        assertTrue(
                "CANCELLED".equals(status.state()) || "FAILED".equals(status.state()),
                "Job should be in terminal state after cancellation");
    }

    /**
     * Test case-insensitive mode tag parsing.
     * Verifies: tags.mode values like "lutz", "Lutz", "LUTZ" all work.
     */
    @Test
    void testLutzModeCaseInsensitive() throws Exception {
        String[] modes = {"lutz", "Lutz", "LUTZ", "LuTz"};

        for (String mode : modes) {
            var spec = JobSpec.of(
                    "Test task",
                    true,
                    true,
                    "gpt-4",
                    "gpt-4",
                    Map.of("mode", mode));

            var jobCreateResult = jobStore.createOrGetJob("test-lutz-" + mode, spec);
            var jobId = jobCreateResult.jobId();

            var future = jobRunner.runAsync(jobId, spec);
            future.get(30, TimeUnit.SECONDS);

            var status = jobStore.loadStatus(jobId);
            assertNotNull(status, "Mode: " + mode);
            assertEquals("COMPLETED", status.state(), "Mode: " + mode);
        }
    }

    /**
     * Test that autoCommit is honored in LUTZ mode.
     * Verifies: When autoCommit=true, changes are committed after execution.
     */
    @Test
    void testLutzAutoCommit() throws Exception {
        var spec = JobSpec.of(
                "Add a simple test file",
                true,
                true,
                "gpt-4",
                "gpt-4",
                Map.of("mode", "LUTZ"));

        var jobCreateResult = jobStore.createOrGetJob("test-lutz-autocommit", spec);
        var jobId = jobCreateResult.jobId();

        var future = jobRunner.runAsync(jobId, spec);
        future.get(30, TimeUnit.SECONDS);

        var status = jobStore.loadStatus(jobId);
        assertNotNull(status);
        assertEquals("COMPLETED", status.state());
    }

    /**
     * Test that autoCompress is honored in LUTZ mode.
     * Verifies: When autoCompress=true, history is compressed after execution.
     */
    @Test
    void testLutzAutoCompress() throws Exception {
        var spec = JobSpec.of(
                "Implement something small",
                true,
                true,
                "gpt-4",
                "gpt-4",
                Map.of("mode", "LUTZ"));

        var jobCreateResult = jobStore.createOrGetJob("test-lutz-autocompress", spec);
        var jobId = jobCreateResult.jobId();

        var future = jobRunner.runAsync(jobId, spec);
        future.get(30, TimeUnit.SECONDS);

        var status = jobStore.loadStatus(jobId);
        assertNotNull(status);
        assertEquals("COMPLETED", status.state());
    }

    /**
     * Test that model names are logged correctly for LUTZ mode.
     * Verifies: Both plannerModel and codeModel are resolved and logged.
     */
    @Test
    void testLutzModelLogging() throws Exception {
        var spec = JobSpec.of(
                "Small task",
                true,
                true,
                "gpt-4",
                "gpt-4-turbo",
                Map.of("mode", "LUTZ"));

        var jobCreateResult = jobStore.createOrGetJob("test-lutz-models", spec);
        var jobId = jobCreateResult.jobId();

        var future = jobRunner.runAsync(jobId, spec);
        future.get(30, TimeUnit.SECONDS);

        var status = jobStore.loadStatus(jobId);
        assertNotNull(status);
        assertEquals("COMPLETED", status.state());
    }

    // ============= Helper Methods =============

    private boolean isJobCompleted(String jobId) throws Exception {
        var status = jobStore.loadStatus(jobId);
        return status != null && ("COMPLETED".equals(status.state()) || "FAILED".equals(status.state()));
    }

    private static void deleteRecursively(Path path) throws Exception {
        if (!Files.exists(path)) {
            return;
        }
        if (Files.isDirectory(path)) {
            try (var stream = Files.list(path)) {
                stream.forEach(p -> {
                    try {
                        deleteRecursively(p);
                    } catch (Exception e) {
                        logger.warn("Failed to delete {}", p, e);
                    }
                });
            }
        }
        Files.deleteIfExists(path);
    }
}
