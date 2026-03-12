package ai.brokk;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.project.MainProject;
import ai.brokk.testutil.TestConsoleIO;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests that ContextManager routes tasks to the correct executors:
 * - getBackgroundTasks() returns the shared project executor
 * - submitBackgroundTask() uses the shared project executor
 * - submitAnalyzerTask() uses the session-local analyzer executor
 */
class ContextManagerExecutorRoutingTest {

    @TempDir
    Path tempDir;

    private MainProject project;
    private ContextManager contextManager;

    @BeforeEach
    void setup() throws Exception {
        Path projectDir = tempDir.resolve("test-project");
        Files.createDirectories(projectDir);

        project = MainProject.forTests(projectDir);
        contextManager = new ContextManager(project);
        contextManager.createHeadless(true, new TestConsoleIO());
    }

    @AfterEach
    void tearDown() {
        if (contextManager != null) {
            contextManager.close();
        }
    }

    @Test
    void getBackgroundTasks_returnsProjectExecutor() {
        ExecutorService cmExecutor = contextManager.getBackgroundTasks();
        ExecutorService projectExecutor = project.getBackgroundExecutor();

        assertNotNull(cmExecutor);
        assertNotNull(projectExecutor);
        assertSame(projectExecutor, cmExecutor, "getBackgroundTasks() should return the project's background executor");
    }

    @Test
    void submitBackgroundTask_executesOnProjectExecutor() throws Exception {
        var latch = new CountDownLatch(1);
        var threadName = new AtomicReference<String>();

        contextManager.submitBackgroundTask("Test task", () -> {
            threadName.set(Thread.currentThread().getName());
            latch.countDown();
        });

        assertTrue(latch.await(5, TimeUnit.SECONDS), "Task should complete");
        assertNotNull(threadName.get());
        assertTrue(
                threadName.get().startsWith("MainProject-"),
                "Task should run on MainProject executor thread, but ran on: " + threadName.get());
    }

    @Test
    void submitAnalyzerTask_executesOnAnalyzerLocalExecutor() throws Exception {
        var latch = new CountDownLatch(1);
        var threadName = new AtomicReference<String>();

        contextManager.submitAnalyzerTask("Analyzer test task", () -> {
            threadName.set(Thread.currentThread().getName());
            latch.countDown();
        });

        assertTrue(latch.await(5, TimeUnit.SECONDS), "Task should complete");
        assertNotNull(threadName.get());
        assertTrue(
                threadName.get().startsWith("AnalyzerLocal"),
                "Analyzer task should run on AnalyzerLocal executor thread, but ran on: " + threadName.get());
    }

    @Test
    void submitAnalyzerTask_triggersBackgroundOutput() throws Exception {
        contextManager.close();

        Path projectDir = tempDir.resolve("tracking-project");
        Files.createDirectories(projectDir);
        project = MainProject.forTests(projectDir);
        contextManager = new ContextManager(project);

        var taskDescription = "Analyzer task with status tracking";
        var statusShown = new CountDownLatch(1);
        var statusCleared = new CountDownLatch(1);
        var sawTaskDescription = new AtomicBoolean(false);
        var threadName = new AtomicReference<String>();

        var recordingConsole = new TestConsoleIO() {
            @Override
            public void backgroundOutput(String backgroundTaskDescription) {
                if (taskDescription.equals(backgroundTaskDescription)) {
                    sawTaskDescription.set(true);
                    statusShown.countDown();
                } else if (backgroundTaskDescription.isEmpty() && sawTaskDescription.get()) {
                    statusCleared.countDown();
                }
            }
        };
        contextManager.setIo(recordingConsole);

        var future = contextManager.submitAnalyzerTask(taskDescription, () -> {
            threadName.set(Thread.currentThread().getName());
        });

        assertTrue(statusShown.await(5, TimeUnit.SECONDS), "Analyzer task should publish background status");
        future.get(5, TimeUnit.SECONDS);
        assertTrue(statusCleared.await(5, TimeUnit.SECONDS), "Analyzer task should clear background status");
        assertNotNull(threadName.get());
        assertTrue(
                threadName.get().startsWith("AnalyzerLocal"),
                "Analyzer task should run on AnalyzerLocal executor thread, but ran on: " + threadName.get());
    }

    @Test
    void backgroundAndAnalyzerTasks_runOnDifferentExecutors() throws Exception {
        var bgLatch = new CountDownLatch(1);
        var analyzerLatch = new CountDownLatch(1);
        var bgThreadName = new AtomicReference<String>();
        var analyzerThreadName = new AtomicReference<String>();

        contextManager.submitBackgroundTask("Background task", () -> {
            bgThreadName.set(Thread.currentThread().getName());
            bgLatch.countDown();
        });

        contextManager.submitAnalyzerTask("Analyzer task", () -> {
            analyzerThreadName.set(Thread.currentThread().getName());
            analyzerLatch.countDown();
        });

        assertTrue(bgLatch.await(5, TimeUnit.SECONDS), "Background task should complete");
        assertTrue(analyzerLatch.await(5, TimeUnit.SECONDS), "Analyzer task should complete");

        assertNotNull(bgThreadName.get());
        assertNotNull(analyzerThreadName.get());

        // Verify they run on different executor pools
        assertTrue(bgThreadName.get().startsWith("MainProject-"), "Background task thread: " + bgThreadName.get());
        assertTrue(
                analyzerThreadName.get().startsWith("AnalyzerLocal"),
                "Analyzer task thread: " + analyzerThreadName.get());
    }

    @Test
    void close_shutsDownAnalyzerLocalExecutor() throws Exception {
        var taskStarted = new CountDownLatch(1);
        var taskCanFinish = new CountDownLatch(1);
        var taskCompleted = new AtomicBoolean(false);

        contextManager.submitAnalyzerTask("Long-running analyzer task", () -> {
            taskStarted.countDown();
            try {
                taskCanFinish.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            taskCompleted.set(true);
        });

        assertTrue(taskStarted.await(5, TimeUnit.SECONDS), "Task should start");

        // Allow task to finish before close
        taskCanFinish.countDown();

        // Close should wait for in-flight tasks
        contextManager.close();
        contextManager = null; // Prevent double-close in tearDown

        assertTrue(taskCompleted.get(), "In-flight analyzer task should complete before close returns");
    }
}
