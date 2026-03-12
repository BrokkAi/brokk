package ai.brokk.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MainProjectBackgroundExecutorTest {

    @TempDir
    Path tempDir;

    @Test
    void getBackgroundExecutor_returnsNonNull() throws Exception {
        Path projectDir = tempDir.resolve("test-project");
        Files.createDirectories(projectDir);

        try (var project = MainProject.forTests(projectDir)) {
            ExecutorService executor = project.getBackgroundExecutor();
            assertNotNull(executor);
            assertFalse(executor.isShutdown());
        }
    }

    @Test
    void getBackgroundExecutor_executesTasksSuccessfully() throws Exception {
        Path projectDir = tempDir.resolve("test-project");
        Files.createDirectories(projectDir);

        try (var project = MainProject.forTests(projectDir)) {
            ExecutorService executor = project.getBackgroundExecutor();

            var latch = new CountDownLatch(1);
            var executed = new AtomicBoolean(false);

            executor.submit(() -> {
                executed.set(true);
                latch.countDown();
            });

            assertTrue(latch.await(5, TimeUnit.SECONDS), "Task should complete within timeout");
            assertTrue(executed.get(), "Task should have executed");
        }
    }

    @Test
    void getBackgroundExecutor_threadNamingIsConsistent() throws Exception {
        Path projectDir = tempDir.resolve("naming-test");
        Files.createDirectories(projectDir);

        try (var project = MainProject.forTests(projectDir)) {
            ExecutorService executor = project.getBackgroundExecutor();

            var latch = new CountDownLatch(1);
            var threadName = new AtomicBoolean(false);

            executor.submit(() -> {
                String name = Thread.currentThread().getName();
                threadName.set(name.startsWith("MainProject-"));
                latch.countDown();
            });

            assertTrue(latch.await(5, TimeUnit.SECONDS));
            assertTrue(threadName.get(), "Thread name should start with 'MainProject-'");
        }
    }

    @Test
    void close_shutsDownExecutorGracefully() throws Exception {
        Path projectDir = tempDir.resolve("close-test");
        Files.createDirectories(projectDir);

        ExecutorService capturedExecutor;
        try (var project = MainProject.forTests(projectDir)) {
            capturedExecutor = project.getBackgroundExecutor();
            assertFalse(capturedExecutor.isShutdown());
        }
        // After close, executor should be shut down
        assertTrue(capturedExecutor.isShutdown());
    }

    @Test
    void close_waitsForInFlightTasks() throws Exception {
        Path projectDir = tempDir.resolve("inflight-test");
        Files.createDirectories(projectDir);

        var taskStarted = new CountDownLatch(1);
        var taskCanFinish = new CountDownLatch(1);
        var taskCompleted = new AtomicBoolean(false);

        var project = MainProject.forTests(projectDir);
        ExecutorService executor = project.getBackgroundExecutor();

        executor.submit(() -> {
            taskStarted.countDown();
            try {
                taskCanFinish.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            taskCompleted.set(true);
        });

        // Wait for task to start
        assertTrue(taskStarted.await(5, TimeUnit.SECONDS));

        // Allow task to finish before close
        taskCanFinish.countDown();

        // Close should wait for task completion
        project.close();

        assertTrue(taskCompleted.get(), "In-flight task should complete before close returns");
        assertTrue(executor.isShutdown());
    }

    @Test
    void multipleTasksExecuteConcurrently() throws Exception {
        Path projectDir = tempDir.resolve("concurrent-test");
        Files.createDirectories(projectDir);

        try (var project = MainProject.forTests(projectDir)) {
            ExecutorService executor = project.getBackgroundExecutor();

            int taskCount = 4;
            var startLatch = new CountDownLatch(taskCount);
            var finishLatch = new CountDownLatch(1);
            var completedCount = new AtomicInteger(0);

            for (int i = 0; i < taskCount; i++) {
                executor.submit(() -> {
                    startLatch.countDown();
                    try {
                        finishLatch.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    completedCount.incrementAndGet();
                });
            }

            // All tasks should start (executor has 4 threads)
            assertTrue(startLatch.await(5, TimeUnit.SECONDS), "All tasks should start concurrently");

            // Let them finish
            finishLatch.countDown();

            // Give time for completion
            Thread.sleep(100);
            assertEquals(taskCount, completedCount.get(), "All tasks should complete");
        }
    }
}
