package ai.brokk.testutil;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TestProjectExecutorTest {

    @TempDir
    Path tempDir;

    @Test
    void getBackgroundExecutor_returnsNonNull() throws Exception {
        Path projectDir = tempDir.resolve("test");
        Files.createDirectories(projectDir);

        var project = new TestProject(projectDir);
        try {
            ExecutorService executor = project.getBackgroundExecutor();
            assertNotNull(executor);
            assertFalse(executor.isShutdown());
        } finally {
            project.close();
        }
    }

    @Test
    void getBackgroundExecutor_returnsSameInstance() throws Exception {
        Path projectDir = tempDir.resolve("test");
        Files.createDirectories(projectDir);

        var project = new TestProject(projectDir);
        try {
            ExecutorService first = project.getBackgroundExecutor();
            ExecutorService second = project.getBackgroundExecutor();
            assertSame(first, second, "Should return the same executor instance");
        } finally {
            project.close();
        }
    }

    @Test
    void getBackgroundExecutor_executesTask() throws Exception {
        Path projectDir = tempDir.resolve("test");
        Files.createDirectories(projectDir);

        var project = new TestProject(projectDir);
        try {
            var latch = new CountDownLatch(1);
            var executed = new AtomicBoolean(false);

            project.getBackgroundExecutor().submit(() -> {
                executed.set(true);
                latch.countDown();
            });

            assertTrue(latch.await(5, TimeUnit.SECONDS));
            assertTrue(executed.get());
        } finally {
            project.close();
        }
    }

    @Test
    void close_shutsDownExecutor() throws Exception {
        Path projectDir = tempDir.resolve("test");
        Files.createDirectories(projectDir);

        var project = new TestProject(projectDir);
        ExecutorService executor = project.getBackgroundExecutor();

        project.close();

        assertTrue(executor.isShutdown());
    }

    @Test
    void close_safeToCallMultipleTimes() throws Exception {
        Path projectDir = tempDir.resolve("test");
        Files.createDirectories(projectDir);

        var project = new TestProject(projectDir);
        project.getBackgroundExecutor();

        project.close();
        project.close(); // Should not throw
    }

    @Test
    void close_safeWhenExecutorNeverAccessed() throws Exception {
        Path projectDir = tempDir.resolve("test");
        Files.createDirectories(projectDir);

        var project = new TestProject(projectDir);
        project.close(); // Should not throw even if executor was never created
    }
}
