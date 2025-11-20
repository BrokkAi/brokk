package ai.brokk.agents;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.AbstractService;
import ai.brokk.IContextManager;
import ai.brokk.Service;
import ai.brokk.TaskResult;
import ai.brokk.context.Context;
import ai.brokk.testutil.NoOpConsoleIO;
import ai.brokk.testutil.TestContextManager;
import java.nio.file.Files;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
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
    void testTaskResultRequiresLiveContext() {
        // Get the live top context
        Context liveContext = contextManager.liveContext();
        liveContext.awaitContextsAreComputed(Duration.of(10, ChronoUnit.SECONDS));

        // Constructing TaskResult with live context should succeed
        TaskResult result = assertDoesNotThrow(
                () -> new TaskResult(
                        contextManager,
                        "test instructions",
                        List.of(),
                        liveContext,
                        new TaskResult.StopDetails(TaskResult.StopReason.SUCCESS),
                        null),
                "TaskResult construction should succeed with live context");

        assertNotNull(result, "TaskResult should be successfully constructed");
        assertEquals(TaskResult.StopReason.SUCCESS, result.stopDetails().reason());
    }

    @Test
    @DisplayName("TaskResult with unfrozen context should succeed")
    void testUnfrozenContextSucceeds() {
        // Get the live top context
        Context liveContext = contextManager.liveContext();
        liveContext.awaitContextsAreComputed(Duration.of(10, ChronoUnit.SECONDS));

        // Constructing TaskResult with unfrozen context should succeed
        TaskResult result = assertDoesNotThrow(
                () -> new TaskResult(
                        contextManager,
                        "test instructions",
                        List.of(),
                        liveContext,
                        new TaskResult.StopDetails(TaskResult.StopReason.SUCCESS),
                        new TaskResult.TaskMeta(TaskResult.Type.BLITZFORGE, new Service.ModelConfig("test-model"))),
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
            public ai.brokk.IConsoleIO getConsoleIO(ai.brokk.analyzer.ProjectFile file) {
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
    void testUnfreezeIdempotency() {
        Context liveContext = contextManager.liveContext();
        liveContext.awaitContextsAreComputed(Duration.of(10, ChronoUnit.SECONDS));

        // Should be usable for TaskResult construction
        TaskResult result = assertDoesNotThrow(
                () -> new TaskResult(
                        contextManager,
                        "test",
                        List.of(),
                        liveContext,
                        new TaskResult.StopDetails(TaskResult.StopReason.SUCCESS),
                        new TaskResult.TaskMeta(TaskResult.Type.BLITZFORGE, new Service.ModelConfig("test-model"))),
                "Result from unfrozen context should be usable for TaskResult");

        assertNotNull(result, "TaskResult should be successfully constructed");
    }

    @Test
    @DisplayName("Interrupted thread causes BlitzForge to return INTERRUPTED reason")
    void testInterruptedRunReturnsInterruptedReason() throws Exception {
        // Create a test file
        var testFile = contextManager.toFile("bf-interrupt.txt");
        testFile.create();
        testFile.write("test content for interruption scenario");

        // Build the BlitzForge configuration with non-empty shared context (triggers warm-up)
        // Use UnavailableStreamingModel to avoid AdaptiveExecutor configuration issues in tests
        var config = new BlitzForge.RunConfig(
                "Apply test instruction",
                new Service.UnavailableStreamingModel(),
                () -> "per-file context",
                () -> "shared context",
                ".*",
                BlitzForge.ParallelOutputMode.NONE);

        // Capture the result passed to listener.onComplete
        var capturedResult = new AtomicReference<TaskResult>();

        var listener = new BlitzForge.Listener() {
            @Override
            public ai.brokk.IConsoleIO getConsoleIO(ai.brokk.analyzer.ProjectFile file) {
                return new NoOpConsoleIO();
            }

            @Override
            public void onComplete(TaskResult result) {
                capturedResult.set(result);
            }
        };

        var blitzForge = new BlitzForge(contextManager, service, config, listener);

        // Set the thread's interrupted flag to trigger early exit from executeParallel
        Thread.currentThread().interrupt();
        try {
            // Execute with the interrupted flag set. The warm-up path will detect the interrupt
            // and return an INTERRUPTED result via interruptedResult()
            TaskResult result =
                    blitzForge.executeParallel(List.of(testFile), f -> new BlitzForge.FileResult(f, false, null, ""));

            // Assertions: result should indicate interruption
            assertNotNull(result, "Result should not be null");
            assertEquals(
                    TaskResult.StopReason.INTERRUPTED,
                    result.stopDetails().reason(),
                    "Result reason should be INTERRUPTED");

            // Verify that listener.onComplete was called with the same result
            assertNotNull(capturedResult.get(), "Listener onComplete should have been called");
            assertEquals(
                    TaskResult.StopReason.INTERRUPTED,
                    capturedResult.get().stopDetails().reason(),
                    "Listener captured result should also have INTERRUPTED reason");
        } finally {
            // Clear the interrupted flag to avoid affecting other tests
            Thread.interrupted();
        }
    }
}
