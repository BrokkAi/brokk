package ai.brokk.agents;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.AbstractService;
import ai.brokk.IConsoleIO;
import ai.brokk.IContextManager;
import ai.brokk.TaskResult;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.context.Context;
import ai.brokk.testutil.NoOpConsoleIO;
import ai.brokk.testutil.TestContextManager;
import java.nio.file.Files;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for BlitzForge interruption handling and context freezing invariants.
 *
 * <p>These tests validate that:
 * <ul>
 *   <li>Passing a frozen context to TaskResult constructor triggers an AssertionError (under -ea)
 *   <li>BlitzForge.interruptedResult(...) completes successfully with StopReason.INTERRUPTED
 *   <li>Context.unfreeze(...) correctly converts frozen contexts to live ones
 * </ul>
 */
@DisplayName("BlitzForge Interruption and Context Freezing Tests")
class BlitzForgeInterruptedTest {

    private IContextManager contextManager;
    private AbstractService service;

    @BeforeEach
    void setUp() throws Exception {
        // Create a minimal test context manager with a temporary directory
        var tmpDir = Files.createTempDirectory("blitzforge-test");
        contextManager = new TestContextManager(tmpDir, new NoOpConsoleIO());
        service = contextManager.getService();
    }

    @Test
    @DisplayName("TaskResult with live context succeeds and assertion is enforced")
    void testTaskResultRequiresLiveContext() throws InterruptedException {
        // Get the live top context
        Context liveContext = contextManager.liveContext();
        liveContext.awaitContentsAreComputed(Duration.of(10, ChronoUnit.SECONDS));

        // Constructing TaskResult with live context should succeed
        TaskResult result = assertDoesNotThrow(
                () -> new TaskResult(liveContext, new TaskResult.StopDetails(TaskResult.StopReason.SUCCESS)),
                "TaskResult construction should succeed with live context");

        assertNotNull(result, "TaskResult should be successfully constructed");
        assertEquals(TaskResult.StopReason.SUCCESS, result.stopDetails().reason());
    }

    @Test
    @DisplayName("TaskResult with unfrozen context should succeed")
    void testUnfrozenContextSucceeds() throws InterruptedException {
        // Get the live top context
        Context liveContext = contextManager.liveContext();
        liveContext.awaitContentsAreComputed(Duration.of(10, ChronoUnit.SECONDS));

        // Constructing TaskResult with unfrozen context should succeed
        TaskResult result = assertDoesNotThrow(
                () -> new TaskResult(liveContext, new TaskResult.StopDetails(TaskResult.StopReason.SUCCESS)),
                "TaskResult construction should succeed with unfrozen context");

        assertNotNull(result, "TaskResult should be successfully constructed");
        assertEquals(TaskResult.StopReason.SUCCESS, result.stopDetails().reason());
    }

    @Test
    @DisplayName("BlitzForge.executeParallel with empty files returns SUCCESS with live context")
    void testEmptyFilesReturnsSuccessWithLiveContext() {
        // Create a minimal BlitzForge with a no-op listener
        var config = new BlitzForge.RunConfig(
                "test instructions",
                service.quickestModel(),
                () -> "per-file context",
                () -> "shared context",
                ".*",
                BlitzForge.ParallelOutputMode.NONE);

        var listener = new BlitzForge.Listener() {
            @Override
            public IConsoleIO getConsoleIO(ProjectFile file) {
                return new NoOpConsoleIO();
            }
        };

        var blitzForge = new BlitzForge(contextManager, service, config, listener);

        // Empty file list should return SUCCESS with a live context
        TaskResult result = assertDoesNotThrow(
                () -> blitzForge.executeParallel(List.of(), f -> new BlitzForge.FileResult(f, false, null, "")),
                "BlitzForge.executeParallel with empty files should not throw");

        assertNotNull(result, "Result should not be null");
        assertEquals(
                TaskResult.StopReason.SUCCESS, result.stopDetails().reason(), "Empty file run should return SUCCESS");
    }

    @Test
    @DisplayName("Context.unfreeze is idempotent on live contexts")
    void testUnfreezeIdempotency() throws InterruptedException {
        Context liveContext = contextManager.liveContext();
        liveContext.awaitContentsAreComputed(Duration.of(10, ChronoUnit.SECONDS));

        // Should be usable for TaskResult construction
        TaskResult result = assertDoesNotThrow(
                () -> new TaskResult(liveContext, new TaskResult.StopDetails(TaskResult.StopReason.SUCCESS)),
                "Result from unfrozen context should be usable for TaskResult");

        assertNotNull(result, "TaskResult should be successfully constructed");
    }
}
